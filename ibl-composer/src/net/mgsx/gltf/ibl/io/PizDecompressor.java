package net.mgsx.gltf.ibl.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PIZ decompression for OpenEXR.
 * Ported from the OpenEXR C++ reference implementation (ImfPizCompressor.cpp, ImfHuf.cpp, ImfWav.cpp).
 * BSD-3-Clause licensed by Contributors to the OpenEXR Project.
 */
class PizDecompressor {

	private static final int USHORT_RANGE = 1 << 16;
	private static final int BITMAP_SIZE = USHORT_RANGE >> 3;
	private static final int HUF_ENCBITS = 16;
	private static final int HUF_DECBITS = 14;
	private static final int HUF_ENCSIZE = (1 << HUF_ENCBITS) + 1;
	private static final int HUF_DECSIZE = 1 << HUF_DECBITS;
	private static final int HUF_DECMASK = HUF_DECSIZE - 1;
	private static final int SHORT_ZEROCODE_RUN = 59;
	private static final int LONG_ZEROCODE_RUN = 63;
	private static final int SHORTEST_LONG_RUN = 2 + LONG_ZEROCODE_RUN - SHORT_ZEROCODE_RUN;

	// --- Wavelet basis functions ---

	// 14-bit decode (no modulo)
	private static void wdec14(short l, short h, short[] out) {
		int hi = h;
		int ai = l + (hi & 1) + (hi >> 1);
		out[0] = (short) ai;
		out[1] = (short) (ai - hi);
	}

	// 16-bit decode (modulo arithmetic)
	private static final int NBITS = 16;
	private static final int A_OFFSET = 1 << (NBITS - 1);
	private static final int MOD_MASK = (1 << NBITS) - 1;

	private static void wdec16(short l, short h, short[] out) {
		int m = l & 0xFFFF;
		int d = h & 0xFFFF;
		int bb = (m - (d >> 1)) & MOD_MASK;
		int aa = (d + bb - A_OFFSET) & MOD_MASK;
		out[1] = (short) bb;
		out[0] = (short) aa;
	}

	// 2D Haar wavelet decode
	static void wav2Decode(short[] buf, int offset, int nx, int ox, int ny, int oy, int mx) {
		boolean w14 = mx < (1 << 14);
		int n = Math.min(nx, ny);
		int p = 1;
		while (p <= n) p <<= 1;
		p >>= 1;
		int p2 = p;
		p >>= 1;

		short[] tmp = new short[2];

		while (p >= 1) {
			int oy1 = oy * p, oy2 = oy * p2;
			int ox1 = ox * p, ox2 = ox * p2;

			// Y loop
			int py = offset;
			int ey = offset + oy * (ny - p2);
			for (; py <= ey; py += oy2) {
				int px = py;
				int ex = py + ox * (nx - p2);
				// X loop (2D)
				for (; px <= ex; px += ox2) {
					int i01 = px + ox1;
					int i10 = px + oy1;
					int i11 = i10 + ox1;
					if (w14) {
						wdec14(buf[px], buf[i10], tmp);
						short i00 = tmp[0]; short ii10 = tmp[1];
						wdec14(buf[i01], buf[i11], tmp);
						short ii01 = tmp[0]; short ii11 = tmp[1];
						wdec14(i00, ii01, tmp);
						buf[px] = tmp[0]; buf[i01] = tmp[1];
						wdec14(ii10, ii11, tmp);
						buf[i10] = tmp[0]; buf[i11] = tmp[1];
					} else {
						wdec16(buf[px], buf[i10], tmp);
						short i00 = tmp[0]; short ii10 = tmp[1];
						wdec16(buf[i01], buf[i11], tmp);
						short ii01 = tmp[0]; short ii11 = tmp[1];
						wdec16(i00, ii01, tmp);
						buf[px] = tmp[0]; buf[i01] = tmp[1];
						wdec16(ii10, ii11, tmp);
						buf[i10] = tmp[0]; buf[i11] = tmp[1];
					}
				}
				// 1D odd column
				if ((nx & p) != 0) {
					int i10 = px + oy1;
					if (w14) { wdec14(buf[px], buf[i10], tmp); }
					else { wdec16(buf[px], buf[i10], tmp); }
					buf[px] = tmp[0]; buf[i10] = tmp[1];
				}
			}
			// 1D odd row
			if ((ny & p) != 0) {
				int px = py;
				int ex = py + ox * (nx - p2);
				for (; px <= ex; px += ox2) {
					int i01 = px + ox1;
					if (w14) { wdec14(buf[px], buf[i01], tmp); }
					else { wdec16(buf[px], buf[i01], tmp); }
					buf[px] = tmp[0]; buf[i01] = tmp[1];
				}
			}
			p2 = p;
			p >>= 1;
		}
	}

	// --- Bitmap / LUT ---

	static int reverseLutFromBitmap(byte[] bitmap, short[] lut) {
		int k = 0;
		for (int i = 0; i < USHORT_RANGE; i++) {
			if (i == 0 || ((bitmap[i >> 3] & (1 << (i & 7))) != 0)) {
				lut[k++] = (short) i;
			}
		}
		int n = k - 1;
		while (k < USHORT_RANGE) lut[k++] = 0;
		return n;
	}

	// --- Huffman decoding ---

	static short[] hufUncompress(byte[] data, int offset, int length, int nRaw) throws IOException {
		if (length < 20) {
			if (nRaw != 0) throw new IOException("PIZ: not enough Huffman data");
			return new short[0];
		}
		ByteBuffer bb = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
		int im = bb.getInt();
		int iM = bb.getInt();
		int tableLength = bb.getInt();
		int nBits = bb.getInt();
		bb.getInt(); // padding

		if (im < 0 || im >= HUF_ENCSIZE || iM < 0 || iM >= HUF_ENCSIZE)
			throw new IOException("PIZ: invalid Huffman table size");

		// Unpack encoding table
		long[] hcode = new long[HUF_ENCSIZE];
		long c = 0;
		int lc = 0;
		int pos = bb.position(); // absolute position in data[]

		for (int i = im; i <= iM; i++) {
			int l = (int) getBits(6, data, pos, c, lc);
			c = lastC; lc = lastLc; pos = lastPos;

			if (l == LONG_ZEROCODE_RUN) {
				int zerun = (int) getBits(8, data, pos, c, lc) + SHORTEST_LONG_RUN;
				c = lastC; lc = lastLc; pos = lastPos;
				if (i + zerun > iM + 1) throw new IOException("PIZ: Huffman table too long");
				for (int z = 0; z < zerun; z++) hcode[i + z] = 0;
				i += zerun - 1;
			} else if (l >= SHORT_ZEROCODE_RUN) {
				int zerun = l - SHORT_ZEROCODE_RUN + 2;
				if (i + zerun > iM + 1) throw new IOException("PIZ: Huffman table too long");
				for (int z = 0; z < zerun; z++) hcode[i + z] = 0;
				i += zerun - 1;
			} else {
				hcode[i] = l;
			}
		}

		hufCanonicalCodeTable(hcode);

		// Build decode table
		int[] hdecLen = new int[HUF_DECSIZE]; // short code length
		int[] hdecLit = new int[HUF_DECSIZE]; // short code value OR long code count
		int[][] hdecP = new int[HUF_DECSIZE][]; // long code table

		for (int i = im; i <= iM; i++) {
			long code = hcode[i] >> 6;
			int cl = (int) (hcode[i] & 63);
			if (cl == 0) continue;
			if (cl > HUF_DECBITS) {
				int idx = (int) (code >> (cl - HUF_DECBITS)) & HUF_DECMASK;
				hdecLit[idx]++;
				int[] old = hdecP[idx];
				int[] np = new int[hdecLit[idx]];
				if (old != null) System.arraycopy(old, 0, np, 0, old.length);
				np[hdecLit[idx] - 1] = i;
				hdecP[idx] = np;
			} else {
				int base = (int) (code << (HUF_DECBITS - cl));
				int count = 1 << (HUF_DECBITS - cl);
				for (int j = 0; j < count; j++) {
					hdecLen[base + j] = cl;
					hdecLit[base + j] = i;
				}
			}
		}

		// Decode data
		int dataStart = 20 + tableLength;
		short[] raw = new short[nRaw];
		int outIdx = 0;
		int rlc = iM; // run-length code is the last symbol

		// Reset bit reader for data section
		c = 0; lc = 0;
		int dPos = offset + dataStart;
		int dEnd = offset + Math.min(length, dataStart + (nBits + 7) / 8);

		while (dPos < dEnd && outIdx < nRaw) {
			c = (c << 8) | (data[dPos++] & 0xFF);
			lc += 8;

			while (lc >= HUF_DECBITS && outIdx < nRaw) {
				int lookup = (int) ((c >> (lc - HUF_DECBITS)) & HUF_DECMASK);

				if (hdecLen[lookup] > 0) {
					// Short code
					lc -= hdecLen[lookup];
					int sym = hdecLit[lookup];
					outIdx = outputSymbol(sym, rlc, raw, outIdx, nRaw, data, c, lc, dPos, dEnd);
					c = lastC; lc = lastLc; dPos = lastPos;
				} else if (hdecP[lookup] != null) {
					// Long code
					boolean found = false;
					for (int j = 0; j < hdecP[lookup].length; j++) {
						int sym = hdecP[lookup][j];
						int cl = (int) (hcode[sym] & 63);
						long code2 = hcode[sym] >> 6;
						while (lc < cl && dPos < dEnd) {
							c = (c << 8) | (data[dPos++] & 0xFF);
							lc += 8;
						}
						if (lc >= cl && ((c >> (lc - cl)) & ((1L << cl) - 1)) == code2) {
							lc -= cl;
							outIdx = outputSymbol(sym, rlc, raw, outIdx, nRaw, data, c, lc, dPos, dEnd);
							c = lastC; lc = lastLc; dPos = lastPos;
							found = true;
							break;
						}
					}
					if (!found) throw new IOException("PIZ: invalid Huffman code");
				} else {
					throw new IOException("PIZ: invalid Huffman code");
				}
			}
		}

		// Handle remaining bits
		int i = (8 - nBits) & 7;
		c >>= i;
		lc -= i;

		while (lc > 0 && outIdx < nRaw) {
			int lookup = (int) ((c << (HUF_DECBITS - lc)) & HUF_DECMASK);
			if (hdecLen[lookup] > 0) {
				lc -= hdecLen[lookup];
				if (lc < 0) break;
				int sym = hdecLit[lookup];
				outIdx = outputSymbol(sym, rlc, raw, outIdx, nRaw, data, c, lc, dPos, dEnd);
				c = lastC; lc = lastLc; dPos = lastPos;
			} else {
				break;
			}
		}

		return raw;
	}

	// Thread-local state for bit reader (avoids returning multiple values)
	private static long lastC;
	private static int lastLc;
	private static int lastPos;

	private static long getBits(int nBits, byte[] data, int pos, long c, int lc) {
		while (lc < nBits) {
			c = (c << 8) | (data[pos++] & 0xFF);
			lc += 8;
		}
		lc -= nBits;
		lastC = c; lastLc = lc; lastPos = pos;
		return (c >> lc) & ((1L << nBits) - 1);
	}

	private static int outputSymbol(int sym, int rlc, short[] out, int outIdx, int maxOut,
			byte[] data, long c, int lc, int dPos, int dEnd) throws IOException {
		if (sym == rlc) {
			// Run-length: read 8 bits for repeat count
			while (lc < 8 && dPos < dEnd) {
				c = (c << 8) | (data[dPos++] & 0xFF);
				lc += 8;
			}
			lc -= 8;
			int cs = (int) ((c >> lc) & 0xFF);
			if (outIdx + cs > maxOut || outIdx == 0)
				throw new IOException("PIZ: Huffman RLE overflow");
			short s = out[outIdx - 1];
			for (int i = 0; i < cs; i++)
				out[outIdx++] = s;
		} else {
			if (outIdx >= maxOut) throw new IOException("PIZ: Huffman output overflow");
			out[outIdx++] = (short) sym;
		}
		lastC = c; lastLc = lc; lastPos = dPos;
		return outIdx;
	}

	private static void hufCanonicalCodeTable(long[] hcode) {
		long[] n = new long[59];
		for (int i = 0; i < HUF_ENCSIZE; i++)
			n[(int) hcode[i]]++;
		long c = 0;
		for (int i = 58; i > 0; i--) {
			long nc = (c + n[i]) >> 1;
			n[i] = c;
			c = nc;
		}
		for (int i = 0; i < HUF_ENCSIZE; i++) {
			int l = (int) hcode[i];
			if (l > 0) hcode[i] = l | (n[l]++ << 6);
		}
	}

	// --- Main PIZ decompress ---

	/**
	 * Decompress a PIZ-compressed block.
	 * @param compressed Raw compressed data (after y-coord and size fields)
	 * @param channels Channel info (sorted alphabetically)
	 * @param width Image width
	 * @param numLines Number of scanlines in this block
	 * @return Decompressed byte data in standard EXR scanline layout
	 */
	static byte[] decompress(byte[] compressed, java.util.List<EXRReader.Channel> channels,
			int width, int numLines) throws IOException {
		ByteBuffer in = ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN);

		// Read bitmap
		int minNonZero = in.getShort() & 0xFFFF;
		int maxNonZero = in.getShort() & 0xFFFF;

		byte[] bitmap = new byte[BITMAP_SIZE];
		if (minNonZero <= maxNonZero) {
			if (maxNonZero >= BITMAP_SIZE)
				throw new IOException("PIZ: invalid bitmap size");
			int count = maxNonZero - minNonZero + 1;
			in.get(bitmap, minNonZero, count);
		}

		short[] lut = new short[USHORT_RANGE];
		int maxValue = reverseLutFromBitmap(bitmap, lut);

		// Setup channel data
		int totalShorts = 0;
		int[] chStart = new int[channels.size()];
		int[] chNx = new int[channels.size()];
		int[] chNy = new int[channels.size()];
		int[] chSize = new int[channels.size()]; // shorts per pixel

		for (int i = 0; i < channels.size(); i++) {
			EXRReader.Channel ch = channels.get(i);
			chStart[i] = totalShorts;
			chNx[i] = width;
			chNy[i] = numLines;
			chSize[i] = ch.bytesPerPixel / 2; // 1 for HALF, 2 for FLOAT
			totalShorts += chNx[i] * chNy[i] * chSize[i];
		}

		// Huffman decode
		int huffLength = in.getInt();
		int huffStart = in.position();
		short[] tmpBuffer = hufUncompress(compressed, huffStart, huffLength, totalShorts);

		// Wavelet decode per channel sub-component
		for (int i = 0; i < channels.size(); i++) {
			for (int j = 0; j < chSize[i]; j++) {
				wav2Decode(tmpBuffer, chStart[i] + j,
						chNx[i], chSize[i],
						chNy[i], chNx[i] * chSize[i],
						maxValue);
			}
		}

		// Apply reverse LUT
		for (int i = 0; i < totalShorts; i++) {
			tmpBuffer[i] = lut[tmpBuffer[i] & 0xFFFF];
		}

		// Reconstruct output bytes (scanline-by-scanline, channel-by-channel)
		int bytesPerScanline = 0;
		for (EXRReader.Channel ch : channels) {
			bytesPerScanline += ch.bytesPerPixel * width;
		}
		byte[] result = new byte[bytesPerScanline * numLines];
		ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

		// Reset channel end pointers
		int[] chEnd = new int[channels.size()];
		System.arraycopy(chStart, 0, chEnd, 0, channels.size());

		for (int y = 0; y < numLines; y++) {
			for (int i = 0; i < channels.size(); i++) {
				int n = chNx[i] * chSize[i];
				for (int x = 0; x < n; x++) {
					out.putShort(tmpBuffer[chEnd[i]++]);
				}
			}
		}

		return result;
	}
}
