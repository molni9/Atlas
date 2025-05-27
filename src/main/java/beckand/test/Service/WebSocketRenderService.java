package beckand.test.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class WebSocketRenderService extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final RenderService renderService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private double lastAzimuth = 0.0;
    private double lastElevation = 0.0;
    private String currentModelId;
    private byte[] currentModelData;
    private static final double MIN_ANGLE_CHANGE = 0.1;  // Уменьшаем для более плавного вращения
    private static final long RENDER_TIMEOUT = 100;  // Таймаут рендеринга в мс

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    @Autowired
    public WebSocketRenderService(RenderService renderService) {
        this.renderService = renderService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String modelId = extractModelId(session);
        if (modelId != null) {
            sessions.put(session.getId(), session);
            currentModelId = modelId;
            
            try {
                // Загружаем модель один раз при подключении
                currentModelData = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(modelId)
                        .build()
                ).readAllBytes();
                
                // Отправляем начальное изображение
                byte[] imageData = renderService.renderModel(
                    modelId,
                    new ByteArrayInputStream(currentModelData),
                    "obj",
                    0.0,
                    0.0
                );
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
            
            // Проверяем минимальное изменение угла
            if (Math.abs(lastAzimuth - azimuth) > MIN_ANGLE_CHANGE || 
                Math.abs(lastElevation - elevation) > MIN_ANGLE_CHANGE) {
                
                lastAzimuth = azimuth;
                lastElevation = elevation;
                
                // Используем сохраненные данные модели
                if (currentModelData != null) {
                    byte[] imageData = renderService.renderModel(
                        currentModelId,
                        new ByteArrayInputStream(currentModelData),
                        "obj",
                        azimuth,
                        elevation
                    );
                    
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
            // Очищаем данные модели только если нет активных соединений
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

    // Добавляем метод для проверки валидности углов
    private boolean isValidAngles(double azimuth, double elevation) {
        return !Double.isNaN(azimuth) && !Double.isNaN(elevation) &&
               !Double.isInfinite(azimuth) && !Double.isInfinite(elevation) &&
               elevation >= -80 && elevation <= 80;
    }
} 