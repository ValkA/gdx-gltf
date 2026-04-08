package net.mgsx.gltf.ibl.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal pure-Java OpenEXR reader.
 * Supports single-part scanline images with HALF or FLOAT pixel types,
 * and NO_COMPRESSION, ZIPS, or ZIP compression.
 */
public class EXRReader {

	public static class EXRResult {
		/** RGB float pixel data, 3 floats per pixel, row-major top-to-bottom */
		public final float[] pixels;
		public final int width;
		public final int height;

		public EXRResult(float[] pixels, int width, int height) {
			this.pixels = pixels;
			this.width = width;
			this.height = height;
		}
	}

	private static final int MAGIC = 20000630;

	// Compression types
	private static final int COMPRESS_NONE = 0;
	private static final int COMPRESS_ZIPS = 2;
	private static final int COMPRESS_ZIP = 3;
	private static final int COMPRESS_PIZ = 4;

	// Pixel types
	private static final int PIXEL_UINT = 0;
	private static final int PIXEL_HALF = 1;
	private static final int PIXEL_FLOAT = 2;

	static class Channel {
		String name;
		int pixelType;
		int bytesPerPixel;
	}

	public static EXRResult read(InputStream rawIn) throws IOException {
		// Read entire file into memory for random access
		byte[] fileData = readAllBytes(rawIn);
		ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);

		// Magic number
		int magic = buf.getInt();
		if (magic != MAGIC) {
			throw new IOException("Not an OpenEXR file (bad magic: 0x" + Integer.toHexString(magic) + ")");
		}

		// Version
		int versionField = buf.getInt();
		int version = versionField & 0xFF;
		boolean tiled = (versionField & 0x200) != 0;
		boolean multiPart = (versionField & 0x1000) != 0;

		if (version != 2) {
			throw new IOException("Unsupported EXR version: " + version);
		}
		if (tiled) {
			throw new IOException("Tiled EXR images are not supported. Please use a scanline image.");
		}
		if (multiPart) {
			throw new IOException("Multi-part EXR files are not supported.");
		}

		// Parse header attributes
		List<Channel> channels = null;
		int compression = -1;
		int dataXMin = 0, dataYMin = 0, dataXMax = 0, dataYMax = 0;

		while (true) {
			String attrName = readNullString(buf);
			if (attrName.isEmpty()) break; // end of header

			String attrType = readNullString(buf);
			int attrSize = buf.getInt();
			int attrEnd = buf.position() + attrSize;

			if ("channels".equals(attrName) && "chlist".equals(attrType)) {
				channels = parseChannelList(buf, attrEnd);
			} else if ("compression".equals(attrName)) {
				compression = buf.get() & 0xFF;
			} else if ("dataWindow".equals(attrName)) {
				dataXMin = buf.getInt();
				dataYMin = buf.getInt();
				dataXMax = buf.getInt();
				dataYMax = buf.getInt();
			}

			buf.position(attrEnd);
		}

		if (channels == null) throw new IOException("EXR file missing 'channels' attribute");
		if (compression < 0) throw new IOException("EXR file missing 'compression' attribute");

		if (compression != COMPRESS_NONE && compression != COMPRESS_ZIPS
				&& compression != COMPRESS_ZIP && compression != COMPRESS_PIZ) {
			String[] names = {"NONE", "RLE", "ZIPS", "ZIP", "PIZ", "PXR24", "B44", "B44A", "DWAA", "DWAB"};
			String cname = compression < names.length ? names[compression] : "UNKNOWN(" + compression + ")";
			throw new IOException("Unsupported EXR compression: " + cname
					+ ". Only NONE, ZIPS, ZIP, and PIZ are supported.");
		}

		int width = dataXMax - dataXMin + 1;
		int height = dataYMax - dataYMin + 1;

		// Sort channels alphabetically (EXR spec requirement)
		Collections.sort(channels, new Comparator<Channel>() {
			@Override
			public int compare(Channel a, Channel b) {
				return a.name.compareTo(b.name);
			}
		});

		// Calculate bytes per pixel row for all channels
		int bytesPerPixelRow = 0;
		for (Channel ch : channels) {
			bytesPerPixelRow += ch.bytesPerPixel * width;
		}

		// Find R, G, B channel indices in the sorted list
		int rIdx = -1, gIdx = -1, bIdx = -1;
		for (int i = 0; i < channels.size(); i++) {
			String n = channels.get(i).name;
			if ("R".equals(n)) rIdx = i;
			else if ("G".equals(n)) gIdx = i;
			else if ("B".equals(n)) bIdx = i;
		}
		if (rIdx < 0 || gIdx < 0 || bIdx < 0) {
			throw new IOException("EXR file must have R, G, B channels. Found: " + channelNames(channels));
		}

		// Read offset table
		int scanlinesPerBlock;
		if (compression == COMPRESS_ZIP || compression == COMPRESS_PIZ) {
			scanlinesPerBlock = 32;
			if (compression == COMPRESS_ZIP) scanlinesPerBlock = 16;
		} else {
			scanlinesPerBlock = 1;
		}

		int numBlocks = (height + scanlinesPerBlock - 1) / scanlinesPerBlock;
		long[] offsets = new long[numBlocks];
		for (int i = 0; i < numBlocks; i++) {
			offsets[i] = buf.getLong();
		}

		// Read pixel data
		float[] result = new float[width * height * 3];

		for (int block = 0; block < numBlocks; block++) {
			buf.position((int) offsets[block]);
			int yCoord = buf.getInt();
			int blockDataSize = buf.getInt();

			int blockStartY = yCoord - dataYMin;
			int blockLines = Math.min(scanlinesPerBlock, height - blockStartY);

			byte[] pixelData;
			int expectedSize = bytesPerPixelRow * blockLines;

			if (compression == COMPRESS_NONE) {
				pixelData = new byte[blockDataSize];
				buf.get(pixelData);
			} else if (compression == COMPRESS_PIZ) {
				byte[] compressed = new byte[blockDataSize];
				buf.get(compressed);
				pixelData = PizDecompressor.decompress(compressed, channels, width, blockLines);
			} else {
				// ZIP or ZIPS: zlib decompress, then undo predictor + reorder
				byte[] compressed = new byte[blockDataSize];
				buf.get(compressed);
				pixelData = decompressZip(compressed, expectedSize);
			}

			// Extract RGB float values from the decompressed/raw pixel data
			extractPixels(pixelData, channels, rIdx, gIdx, bIdx, width, blockLines, result,
					blockStartY * width * 3);
		}

		return new EXRResult(result, width, height);
	}

	private static List<Channel> parseChannelList(ByteBuffer buf, int end) throws IOException {
		List<Channel> channels = new ArrayList<Channel>();
		while (buf.position() < end) {
			String name = readNullString(buf);
			if (name.isEmpty()) break;

			Channel ch = new Channel();
			ch.name = name;
			ch.pixelType = buf.getInt();

			if (ch.pixelType == PIXEL_HALF) {
				ch.bytesPerPixel = 2;
			} else if (ch.pixelType == PIXEL_FLOAT) {
				ch.bytesPerPixel = 4;
			} else if (ch.pixelType == PIXEL_UINT) {
				ch.bytesPerPixel = 4;
			} else {
				throw new IOException("Unknown pixel type: " + ch.pixelType + " for channel " + name);
			}

			buf.get(); // pLinear
			buf.get(); buf.get(); buf.get(); // reserved
			buf.getInt(); // xSampling
			buf.getInt(); // ySampling

			channels.add(ch);
		}
		return channels;
	}

	private static void extractPixels(byte[] data, List<Channel> channels,
			int rIdx, int gIdx, int bIdx, int width, int numLines,
			float[] result, int resultOffset) {
		ByteBuffer pixBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

		for (int line = 0; line < numLines; line++) {
			// Calculate byte offset for each channel in this scanline
			int lineStart = 0;
			for (int l = 0; l < line; l++) {
				for (Channel ch : channels) {
					lineStart += ch.bytesPerPixel * width;
				}
			}

			// For each pixel, extract R, G, B
			int[] channelOffsets = new int[channels.size()];
			int off = lineStart;
			for (int i = 0; i < channels.size(); i++) {
				channelOffsets[i] = off;
				off += channels.get(i).bytesPerPixel * width;
			}

			for (int x = 0; x < width; x++) {
				float r = readChannelPixel(pixBuf, channels.get(rIdx), channelOffsets[rIdx], x);
				float g = readChannelPixel(pixBuf, channels.get(gIdx), channelOffsets[gIdx], x);
				float b = readChannelPixel(pixBuf, channels.get(bIdx), channelOffsets[bIdx], x);

				int idx = resultOffset + (line * width + x) * 3;
				result[idx] = r;
				result[idx + 1] = g;
				result[idx + 2] = b;
			}
		}
	}

	private static float readChannelPixel(ByteBuffer buf, Channel ch, int channelOffset, int x) {
		int pos = channelOffset + x * ch.bytesPerPixel;
		if (ch.pixelType == PIXEL_HALF) {
			return halfToFloat(buf.getShort(pos));
		} else if (ch.pixelType == PIXEL_FLOAT) {
			return buf.getFloat(pos);
		} else {
			// UINT — convert to float
			return (float)(buf.getInt(pos) & 0xFFFFFFFFL);
		}
	}

	/**
	 * Decompress ZIP/ZIPS block: zlib inflate, then undo predictor and byte reorder.
	 */
	private static byte[] decompressZip(byte[] compressed, int expectedSize) throws IOException {
		// 1. zlib inflate
		Inflater inflater = new Inflater();
		inflater.setInput(compressed);
		byte[] tmp = new byte[expectedSize];
		try {
			int n = inflater.inflate(tmp);
			if (n != expectedSize) {
				// Try with a dynamic buffer
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				bos.write(tmp, 0, n);
				byte[] chunk = new byte[4096];
				while (!inflater.finished()) {
					int cn = inflater.inflate(chunk);
					bos.write(chunk, 0, cn);
				}
				tmp = bos.toByteArray();
			}
		} catch (DataFormatException e) {
			throw new IOException("Zlib decompression failed: " + e.getMessage(), e);
		} finally {
			inflater.end();
		}

		int size = tmp.length;

		// 2. Undo predictor (delta decode with +128 bias)
		int p = tmp[0] & 0xFF;
		for (int i = 1; i < size; i++) {
			int d = (tmp[i] & 0xFF) + p + 128;
			p = d;
			tmp[i] = (byte) d;
		}

		// 3. Undo byte interleave: first half has even-indexed bytes, second half has odd-indexed
		byte[] result = new byte[size];
		int t1 = 0;
		int t2 = (size + 1) / 2;
		int s = 0;
		while (s < size) {
			if (s < size) result[s++] = tmp[t1++];
			if (s < size) result[s++] = tmp[t2++];
		}

		return result;
	}

	/**
	 * Convert IEEE 754 half-precision float (16-bit) to single-precision float (32-bit).
	 */
	static float halfToFloat(short halfBits) {
		int h = halfBits & 0xFFFF;
		int sign = (h >>> 15) & 1;
		int exp = (h >>> 10) & 0x1F;
		int mant = h & 0x3FF;

		if (exp == 0) {
			if (mant == 0) {
				// Zero
				return Float.intBitsToFloat(sign << 31);
			}
			// Subnormal: normalize
			while ((mant & 0x400) == 0) {
				mant <<= 1;
				exp--;
			}
			exp++;
			mant &= 0x3FF;
			return Float.intBitsToFloat((sign << 31) | ((exp + 127 - 15) << 23) | (mant << 13));
		} else if (exp == 31) {
			// Inf / NaN
			return Float.intBitsToFloat((sign << 31) | 0x7F800000 | (mant << 13));
		}
		// Normalized
		return Float.intBitsToFloat((sign << 31) | ((exp + 127 - 15) << 23) | (mant << 13));
	}

	private static String readNullString(ByteBuffer buf) {
		StringBuilder sb = new StringBuilder();
		while (buf.hasRemaining()) {
			byte b = buf.get();
			if (b == 0) break;
			sb.append((char)(b & 0xFF));
		}
		return sb.toString();
	}

	private static String channelNames(List<Channel> channels) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < channels.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(channels.get(i).name);
		}
		return sb.toString();
	}

	private static byte[] readAllBytes(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) {
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}
}
