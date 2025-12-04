package beckand.test.websocket;

import beckand.test.DTO.FileDTO;
import beckand.test.Service.FileService;
import beckand.test.Service.RenderService;
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

import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RenderWebSocketHandler extends TextWebSocketHandler {

    private final FileService fileService;
    private final RenderService renderService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Render socket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Render socket closed: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RenderCommand command;
        try {
            command = objectMapper.readValue(message.getPayload(), RenderCommand.class);
        } catch (Exception e) {
            log.warn("Invalid render command payload: {}", message.getPayload());
            sendError(session, "Invalid payload: " + e.getMessage());
            return;
        }

        if (!StringUtils.hasText(command.objectKey())) {
            sendError(session, "objectKey is required");
            return;
        }

        try {
            FileDTO info = fileService.getFileInfo(command.objectKey());
            try (InputStream stream = fileService.getFileContent(command.objectKey())) {
                byte[] modelBytes = stream.readAllBytes();
                byte[] jpeg = renderService.renderModel(
                        command.objectKey(),
                        modelBytes,
                        info.getFileType(),
                        command.azimuthOrDefault(),
                        command.elevationOrDefault()
                );
                RenderFrame frame = new RenderFrame(
                        command.frameIdOrDefault(),
                        command.objectKey(),
                        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg),
                        command.azimuthOrDefault(),
                        command.elevationOrDefault(),
                        System.currentTimeMillis()
                );
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
            }
        } catch (Exception e) {
            log.error("Render error for {}: {}", command.objectKey(), e.getMessage());
            sendError(session, "Render error: " + e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "error", message,
                    "timestamp", System.currentTimeMillis()
            ))));
        } catch (Exception ignored) {
            log.warn("Failed to send error frame: {}", message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RenderCommand(
            @JsonProperty("objectKey") String objectKey,
            @JsonProperty("azimuth") Double azimuth,
            @JsonProperty("elevation") Double elevation,
            @JsonProperty("zoom") Double zoom,
            @JsonProperty("frameId") String frameId
    ) {
        double azimuthOrDefault() {
            return azimuth == null ? 0 : azimuth;
        }

        double elevationOrDefault() {
            return elevation == null ? 0 : elevation;
        }

        String frameIdOrDefault() {
            return StringUtils.hasText(frameId) ? frameId : UUID.randomUUID().toString();
        }
    }

    private record RenderFrame(
            String frameId,
            String objectKey,
            String image,
            double azimuth,
            double elevation,
            long renderedAt
    ) {
    }
}

