package beckand.test.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.ServerEndpoint;
import org.springframework.web.socket.server.annotation.OnMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ServerEndpoint("/websocket")
public class WebSocketRenderService extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final RenderService renderService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private double lastAzimuth = 0.0;
    private double lastElevation = 0.0;
    private String currentModelId;
    private String bucketName;
    private S3Client s3Client;

    @Autowired
    public WebSocketRenderService(RenderService renderService) {
        this.renderService = renderService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String modelId = extractModelId(session);
        if (modelId != null) {
            sessions.put(session.getId(), session);
            log.info("WebSocket соединение установлено для модели: {}, sessionId: {}", modelId, session.getId());
            
            // Отправляем начальное изображение сразу после установки соединения
            try {
                byte[] modelData = renderService.getModelData(modelId);
                if (modelData != null) {
                    byte[] imageData = renderService.renderModel(
                        modelId,
                        new ByteArrayInputStream(modelData),
                        "obj",
                        0.0,  // начальный азимут
                        0.0   // начальная высота
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

    @OnMessage
    public void handleMessage(String message, Session session) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            double azimuth = jsonNode.get("azimuth").asDouble();
            double elevation = jsonNode.get("elevation").asDouble();
            
            log.info("Получено WebSocket сообщение: {}", message);
            log.info("Обработка позиции: azimuth={}, elevation={}", azimuth, elevation);

            // Проверяем, достаточно ли изменились углы
            if (Math.abs(lastAzimuth - azimuth) > 0.5 || Math.abs(lastElevation - elevation) > 0.5) {
                lastAzimuth = azimuth;
                lastElevation = elevation;
                
                // Получаем модель из S3
                S3Object s3Object = s3Client.getObject(bucketName, currentModelId);
                try (InputStream modelStream = s3Object.getObjectContent()) {
                    // Рендерим модель
                    byte[] imageData = renderService.renderModel(
                        currentModelId,
                        modelStream,
                        "obj",
                        azimuth,
                        elevation
                    );
                    
                    // Отправляем изображение клиенту
                    session.getBasicRemote().sendBinary(ByteBuffer.wrap(imageData));
                    log.info("Изображение отправлено клиенту, размер: {} байт", imageData.length);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка обработки WebSocket сообщения", e);
            try {
                session.getBasicRemote().sendText("Ошибка: " + e.getMessage());
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

    private String extractModelId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] parts = path.split("/");
        if (parts.length >= 3) {
            return parts[parts.length - 1];
        }
        return null;
    }
} 