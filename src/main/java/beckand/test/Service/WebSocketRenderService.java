package beckand.test.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

@Slf4j
@Service
public class WebSocketRenderService extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private double lastAzimuth = 0.0;
    private double lastElevation = 0.0;
    private String currentModelId;
    private byte[] currentModelData;
    private static final double MIN_ANGLE_CHANGE = 0.1;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ObjectProvider<LwjglEglRenderer> eglRendererProvider;

    @Value("${minio.bucket}")
    private String bucketName;

    @Value("${render.width:960}")
    private int renderWidth;

    @Value("${render.height:540}")
    private int renderHeight;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String modelId = extractModelId(session);
        if (modelId != null) {
            sessions.put(session.getId(), session);
            currentModelId = modelId;

            try {
                currentModelData = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(modelId)
                        .build()
                ).readAllBytes();

                LwjglEglRenderer eglRenderer = eglRendererProvider.getIfAvailable();
                byte[] imageData;
                if (eglRenderer != null) {
                    imageData = eglRenderer.renderFrame(renderWidth, renderHeight, 0.0, 0.0);
                } else {
                    imageData = placeholderImage();
                }
                session.sendMessage(new BinaryMessage(imageData));

            } catch (Exception e) {
                log.error("Ошибка при установке соединения", e);
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ex) {
                    log.error("Error closing WebSocket session", ex);
                }
            }
        } else {
            try {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid model ID"));
            } catch (IOException e) {
                log.error("Error closing WebSocket session", e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message.getPayload());
            double azimuth = jsonNode.get("azimuth").asDouble();
            double elevation = jsonNode.get("elevation").asDouble();

            if (Math.abs(lastAzimuth - azimuth) > MIN_ANGLE_CHANGE ||
                Math.abs(lastElevation - elevation) > MIN_ANGLE_CHANGE) {

                lastAzimuth = azimuth;
                lastElevation = elevation;

                if (currentModelData != null) {
                    LwjglEglRenderer eglRenderer = eglRendererProvider.getIfAvailable();
                    byte[] imageData = (eglRenderer != null)
                            ? eglRenderer.renderFrame(renderWidth, renderHeight, azimuth, elevation)
                            : placeholderImage();
                    session.sendMessage(new BinaryMessage(imageData));
                }
            }
        } catch (Exception e) {
            log.error("Ошибка обработки WebSocket сообщения", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        if (sessions.isEmpty()) {
            currentModelData = null;
            currentModelId = null;
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket ошибка для sessionId={}: {}", session.getId(), exception.getMessage());
        sessions.remove(session.getId());
    }

    private String extractModelId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] parts = path.split("/");
        if (parts.length >= 3) {
            return parts[parts.length - 1];
        }
        return null;
    }

    private byte[] placeholderImage() throws IOException {
        BufferedImage bi = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 640, 360);
        g.setColor(Color.GRAY);
        g.drawString("GPU renderer disabled", 16, 24);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "JPEG", baos);
        return baos.toByteArray();
    }
} 