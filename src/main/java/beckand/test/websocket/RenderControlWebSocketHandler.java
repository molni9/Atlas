package beckand.test.websocket;

import beckand.test.Service.CameraStateService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * Лёгкий WebSocket-хэндлер только для управления камерой.
 * Получает от клиента objectKey + углы и пока что просто логирует и отвечает ack.
 * Позже сюда можно подвязать реальный потоковый рендер/WebRTC.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenderControlWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final CameraStateService cameraStateService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Render control socket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Render control socket closed: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ControlCommand command;
        try {
            command = objectMapper.readValue(message.getPayload(), ControlCommand.class);
        } catch (Exception e) {
            log.warn("Invalid control payload: {}", message.getPayload());
            sendJson(session, Map.of(
                    "error", "invalid payload: " + e.getMessage()
            ));
            return;
        }

        if (!StringUtils.hasText(command.objectKey())) {
            sendJson(session, Map.of("error", "objectKey is required"));
            return;
        }

        double az = command.azimuth() == null ? 0.0 : command.azimuth();
        double el = command.elevation() == null ? 0.0 : command.elevation();

        // Обновляем состояние камеры для этой модели
        cameraStateService.updatePose(command.objectKey(), az, el);
        log.debug("Control for {} -> azimuth={}, elevation={}", command.objectKey(), az, el);

        sendJson(session, Map.of(
                "ok", true,
                "objectKey", command.objectKey(),
                "azimuth", az,
                "elevation", el
        ));
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send WS control response", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ControlCommand(
            @JsonProperty("objectKey") String objectKey,
            @JsonProperty("azimuth") Double azimuth,
            @JsonProperty("elevation") Double elevation
    ) {
    }
}


