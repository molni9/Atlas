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

@Slf4j
@Service
public class WebSocketRenderService extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final RenderService renderService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private double lastAzimuth = 0.0;
    private double lastElevation = 0.0;
    private String currentModelId;

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
            log.info("WebSocket соединение установлено для модели: {}, sessionId: {}", modelId, session.getId());
            
            // Отправляем начальное изображение
            try {
                byte[] modelData = renderService.getModelData(modelId);
                if (modelData != null) {
                    byte[] imageData = renderService.renderModel(
                        modelId,
                        new ByteArrayInputStream(modelData),
                        "obj",
                        0.0,
                        0.0
                    );
                    session.sendMessage(new BinaryMessage(imageData));
                    log.info("Отправлено начальное изображение");
                }
            } catch (Exception e) {
                log.error("Ошибка при отправке начального изображения", e);
            }
        } else {
            log.error("Не удалось установить WebSocket соединение: неверный modelId");
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
            
            log.info("Получено WebSocket сообщение: {}", message.getPayload());
            log.info("Обработка позиции: azimuth={}, elevation={}", azimuth, elevation);

            // Проверяем, достаточно ли изменились углы
            if (Math.abs(lastAzimuth - azimuth) > 0.5 || Math.abs(lastElevation - elevation) > 0.5) {
                lastAzimuth = azimuth;
                lastElevation = elevation;
                
                // Получаем модель из MinIO
                try (var inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(currentModelId)
                        .build()
                )) {
                    // Рендерим модель
                    byte[] imageData = renderService.renderModel(
                        currentModelId,
                        inputStream,
                        "obj",
                        azimuth,
                        elevation
                    );
                    
                    // Отправляем изображение клиенту
                    session.sendMessage(new BinaryMessage(imageData));
                    log.info("Изображение отправлено клиенту, размер: {} байт", imageData.length);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка обработки WebSocket сообщения", e);
            try {
                session.sendMessage(new TextMessage("Ошибка: " + e.getMessage()));
            } catch (IOException ex) {
                log.error("Ошибка отправки сообщения об ошибке", ex);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket соединение закрыто: sessionId={}, status={}", session.getId(), status);
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
} 