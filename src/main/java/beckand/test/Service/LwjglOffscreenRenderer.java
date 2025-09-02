package beckand.test.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

public class LwjglOffscreenRenderer {

	public byte[] renderPreview(int width, int height, String title, double azimuth, double elevation) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.setColor(Color.DARK_GRAY);
			g.drawString("LWJGL renderer stub", 16, 24);
			g.drawString("az=" + Math.round(azimuth) + ", el=" + Math.round(elevation), 16, 44);
			g.setColor(new Color(60, 120, 220));
			int cx = width / 2;
			int cy = height / 2;
			int r = Math.min(width, height) / 4;
			g.fillOval(cx - r, cy - r, r * 2, r * 2);
			g.setColor(Color.BLACK);
			g.drawOval(cx - r, cy - r, r * 2, r * 2);
		} finally {
			g.dispose();
		}
		return encodeJpeg(image, 0.85f);
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