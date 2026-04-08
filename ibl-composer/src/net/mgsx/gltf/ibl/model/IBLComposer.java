package net.mgsx.gltf.ibl.model;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

import net.mgsx.gltf.ibl.exceptions.FrameBufferError;
import net.mgsx.gltf.ibl.io.EXRReader;
import net.mgsx.gltf.ibl.io.RGBE;
import net.mgsx.gltf.ibl.io.RGBE.Header;

public class IBLComposer implements Disposable {

	public Header hdrHeader;
	/** Float RGB pixel data (3 floats per pixel) for the loaded HDR/EXR image */
	private float[] floatPixels;
	private int imageWidth;
	private int imageHeight;
	private Pixmap pixmapRaw;
	private Texture textureRaw;
	private EnvironmentBaker environmentBaker;
	private IrradianceBaker irradianceBaker;
	private RadianceBaker radianceBaker;
	private Cubemap irradianceMap;
	private Cubemap radianceMap;
	private BRDFBaker brdfBaker;
	private Texture brdfMap;
	private Texture builtinBRDF;
	
	public IBLComposer() {
		environmentBaker = new EnvironmentBaker();
		irradianceBaker = new IrradianceBaker();
		radianceBaker = new RadianceBaker();
		brdfBaker = new BRDFBaker();
		builtinBRDF = new Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"));
	}
	
	public void loadHDR(FileHandle file) throws IOException{
		DataInputStream in = null;
		try{
			in = new DataInputStream(new BufferedInputStream(file.read()));
			hdrHeader = RGBE.readHeader(in);
			byte[] hdrData = new byte[hdrHeader.getWidth() * hdrHeader.getHeight() * 4];
			RGBE.readPixelsRawRLE(in, hdrData, 0, hdrHeader.getWidth(), hdrHeader.getHeight());
			
			// Convert RGBE bytes to float RGB
			imageWidth = hdrHeader.getWidth();
			imageHeight = hdrHeader.getHeight();
			floatPixels = new float[imageWidth * imageHeight * 3];
			float[] rgb = new float[3];
			for(int i=0, p=0 ; i<hdrData.length ; i+=4, p+=3){
				RGBE.rgbe2float(rgb, hdrData, i);
				floatPixels[p] = rgb[0];
				floatPixels[p+1] = rgb[1];
				floatPixels[p+2] = rgb[2];
			}
		}finally{
			if(in != null) in.close();
		}
	}

	public void loadEXR(FileHandle file) throws IOException{
		BufferedInputStream in = null;
		try{
			in = new BufferedInputStream(file.read());
			EXRReader.EXRResult result = EXRReader.read(in);
			imageWidth = result.width;
			imageHeight = result.height;
			floatPixels = result.pixels;
			
			// Create a synthetic header for UI display
			hdrHeader = new Header(imageWidth, imageHeight);
		}finally{
			if(in != null) in.close();
		}
	}

	/**
	 * Load either HDR or EXR based on file extension.
	 */
	public void loadImage(FileHandle file) throws IOException {
		String ext = file.extension().toLowerCase();
		if("exr".equals(ext)){
			loadEXR(file);
		}else{
			loadHDR(file);
		}
	}

	@Override
	public void dispose() {
		if(pixmapRaw != null) pixmapRaw.dispose();
		if(textureRaw != null) textureRaw.dispose();
		if(irradianceMap != null) irradianceMap.dispose();
		if(radianceMap != null) radianceMap.dispose();
		if(builtinBRDF != null) builtinBRDF.dispose();
		environmentBaker.dispose();
		irradianceBaker.dispose();
		radianceBaker.dispose();
		brdfBaker.dispose();
	}
	
	public Texture getHDRTexture() {
		if(textureRaw == null){
        	GLOnlyTextureData data = new GLOnlyTextureData(imageWidth, imageHeight, 0, GL30.GL_RGB32F, GL30.GL_RGB, GL30.GL_FLOAT);
        	textureRaw = new Texture(data);
        	FloatBuffer buffer = BufferUtils.newFloatBuffer(imageWidth * imageHeight * 3);
        	buffer.put(floatPixels);
        	buffer.flip();
        	textureRaw.bind();
        	Gdx.gl.glTexImage2D(textureRaw.glTarget, 0, GL30.GL_RGB32F, imageWidth, imageHeight, 0, GL30.GL_RGB, GL30.GL_FLOAT, buffer);
		}
		return textureRaw;
	}

	public Cubemap getEnvMap(int size, float exposure, float gamma, boolean rgbm){
		getHDRTexture();
		return environmentBaker.getEnvMap(textureRaw, size, exposure, gamma, rgbm);
	}

	public Array<Pixmap> getEnvMapPixmaps(int size, float exposure, float gamma, boolean rgbm) {
		getHDRTexture();
		return environmentBaker.createEnvMapPixmaps(textureRaw, size, exposure, gamma, rgbm);
	}

	public Cubemap getIrradianceMap(int size, float sampleDelta, boolean rgbm){
		Cubemap cubemap = environmentBaker.getLastMap();
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
		if(irradianceMap != null) irradianceMap.dispose();
		try{
			irradianceMap = irradianceBaker.createIrradiance(cubemap, size, sampleDelta, rgbm);
		}catch(IllegalStateException e){
			throw new FrameBufferError(e);
		}
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		return irradianceMap;
	}

	public Array<Pixmap> getIrradianceMapPixmaps(int size, float sampleDelta, boolean rgbm) {
		Cubemap cubemap = environmentBaker.getLastMap();
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
		Array<Pixmap> result = irradianceBaker.createPixmaps(cubemap, size, sampleDelta, rgbm);
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		return result;
	}

	public Array<Pixmap> getRadianceMapPixmaps(int size, int sampleCount, boolean rgbm) {
		Cubemap cubemap = environmentBaker.getLastMap();
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
		Array<Pixmap> result = radianceBaker.createPixmaps(cubemap, size, sampleCount, rgbm);
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		return result;
	}

	public Cubemap getRadianceMap(int size, int sampleCount, boolean rgbm){
		Cubemap cubemap = environmentBaker.getLastMap();
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
		if(radianceMap != null) radianceMap.dispose();
		try{
			radianceMap = radianceBaker.createRadiance(cubemap, size, sampleCount, rgbm);
		}catch(IllegalStateException e){
			throw new FrameBufferError(e);
		}
		if (rgbm) cubemap.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		return radianceMap;
	}

	public Texture getBRDFMap(int size, boolean rg16) {
		if(brdfMap != null && brdfMap != builtinBRDF) brdfMap.dispose();
		try{
			brdfMap = brdfBaker.createBRDF(size, rg16);
		}catch(IllegalStateException e){
			brdfMap = new Texture(1, 1, Format.RGB888);
			throw new FrameBufferError(e);
		}
		return brdfMap;
	}

	public Texture getDefaultBRDFMap() {
		return builtinBRDF;
	}

	public Pixmap getBRDFPixmap(int size, boolean brdf16) {
		return brdfBaker.createBRDFPixmap(size, brdf16);
	}

}
