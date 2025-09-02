package beckand.test.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Iterator;

import static org.lwjgl.egl.EGL.*;
import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL12.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;

@Slf4j
@Service
@org.springframework.context.annotation.Profile("gpu")
public class LwjglEglRenderer {

	@Value("${render.jpeg.quality:0.85}")
	private float jpegQuality;

	private long eglDisplay;
	private long eglContext;
	private long eglSurface;
	private EGLCapabilities eglCaps;
	private int surfaceWidth = -1;
	private int surfaceHeight = -1;

	@PostConstruct
	public void init() {
		EGL.create();
		eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		if (eglDisplay == EGL_NO_DISPLAY) {
			throw new IllegalStateException("Failed to get EGL display");
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer major = stack.mallocInt(1);
			IntBuffer minor = stack.mallocInt(1);
			if (!eglInitialize(eglDisplay, major, minor)) {
				throw new IllegalStateException("Failed to initialize EGL");
			}
			log.info("EGL initialized: {}.{}", major.get(0), minor.get(0));
		}
		eglCaps = EGL.createDisplayCapabilities(eglDisplay);
	}

	@PreDestroy
	public void destroy() {
		try {
			if (eglSurface != EGL_NO_SURFACE) {
				eglDestroySurface(eglDisplay, eglSurface);
			}
			if (eglContext != EGL_NO_CONTEXT) {
				eglDestroyContext(eglDisplay, eglContext);
			}
		} catch (Throwable ignored) {
		} finally {
			if (eglDisplay != EGL_NO_DISPLAY) {
				eglTerminate(eglDisplay);
			}
			EGL.destroy();
		}
	}

	private void ensureSurface(int width, int height) {
		if (width == surfaceWidth && height == surfaceHeight && eglSurface != EGL_NO_SURFACE) {
			return;
		}
		if (eglSurface != EGL_NO_SURFACE) {
			eglDestroySurface(eglDisplay, eglSurface);
			eglSurface = EGL_NO_SURFACE;
		}
		if (eglContext != EGL_NO_CONTEXT) {
			eglDestroyContext(eglDisplay, eglContext);
			eglContext = EGL_NO_CONTEXT;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer numConfigs = stack.mallocInt(1);
			PointerBuffer configs = stack.mallocPointer(64);

			IntBuffer attribs = stack.ints(
				EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
				EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
				EGL_RED_SIZE, 8,
				EGL_GREEN_SIZE, 8,
				EGL_BLUE_SIZE, 8,
				EGL_ALPHA_SIZE, 0,
				EGL_DEPTH_SIZE, 24,
				EGL_NONE
			);
			if (!eglChooseConfig(eglDisplay, attribs, configs, numConfigs)) {
				throw new IllegalStateException("Failed to choose EGL config");
			}
			long eglConfig = configs.get(0);

			IntBuffer pbufferAttribs = stack.ints(
				EGL_WIDTH, width,
				EGL_HEIGHT, height,
				EGL_NONE
			);
			eglSurface = eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs);
			if (eglSurface == EGL_NO_SURFACE) {
				throw new IllegalStateException("Failed to create EGL pbuffer surface");
			}

			if (!eglBindAPI(EGL_OPENGL_API)) {
				throw new IllegalStateException("Failed to bind OpenGL API");
			}
			eglContext = eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, stack.ints(EGL_NONE));
			if (eglContext == EGL_NO_CONTEXT) {
				throw new IllegalStateException("Failed to create EGL context");
			}

			if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
				throw new IllegalStateException("Failed to make EGL context current");
			}
		}

		GL.createCapabilities();
		glViewport(0, 0, width, height);
		this.surfaceWidth = width;
		this.surfaceHeight = height;
		log.info("EGL pbuffer created: {}x{}", width, height);
	}

	public byte[] renderFrame(int width, int height, double azimuth, double elevation) throws IOException {
		ensureSurface(width, height);

		float r = (float) ((Math.sin(Math.toRadians(azimuth)) + 1.0) * 0.5);
		float g = (float) ((Math.sin(Math.toRadians(elevation)) + 1.0) * 0.5);
		float b = 0.6f;
		glClearColor(r, g, b, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int[] pixels = new int[width * height];
		IntBuffer buf = memAllocInt(width * height);
		try {
			glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			buf.get(pixels);
		} finally {
			buf.rewind();
			memFree(buf);
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (int y = 0; y < height; y++) {
			int srcY = height - 1 - y;
			for (int x = 0; x < width; x++) {
				int p = pixels[srcY * width + x];
				int r8 = (p) & 0xFF;
				int g8 = (p >> 8) & 0xFF;
				int b8 = (p >> 16) & 0xFF;
				dst[y * width + x] = (r8 << 16) | (g8 << 8) | b8;
			}
		}
		return encodeJpeg(image, jpegQuality);
	}

	private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("No JPEG writers available");
		}
		ImageWriter writer = writers.next();
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
			writer.setOutput(ios);
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));
			}
			writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
			writer.dispose();
			return baos.toByteArray();
		}
	}
} 