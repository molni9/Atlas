package beckand.test.websocket;

import beckand.test.DTO.file.FileDTO;
import beckand.test.Service.file.FileService;
import beckand.test.Service.render.RenderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RenderWebSocketHandler implements WebSocketHandler {

    private final FileService fileService;
    private final RenderService renderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("WebSocket connected: {}", session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        try {
            if (message instanceof TextMessage textMessage) {
                long tAll0 = System.nanoTime();
                URI uri = session.getUri();
                if (uri == null) return;
                String path = uri.getPath();
                if (path == null) return;
                String modelId = path.substring(path.lastIndexOf('/') + 1).split("\\?")[0];

                JsonNode root = objectMapper.readTree(textMessage.getPayload());
                String type = root.path("type").asText("");
                if (!"rotate".equals(type)) return;
                double azimuth = root.path("azimuth").asDouble(0);
                double elevation = root.path("elevation").asDouble(0);
                boolean finalFrame = root.path("final").asBoolean(false);

                // Рендер только на сервере — отправляем клиенту только JPEG
                long tMeta0 = System.nanoTime();
                FileDTO info = fileService.getFileInfo(modelId);
                long metaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tMeta0);
                try (InputStream is = fileService.getFileContent(modelId)) {
                    long tRender0 = System.nanoTime();
                    byte[] jpeg = renderService.renderModelAdaptive(modelId, is, info.getFileType(), azimuth, elevation, finalFrame);
                    long renderMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tRender0);
                    long tSend0 = System.nanoTime();
                    session.sendMessage(new BinaryMessage(jpeg));
                    long sendMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tSend0);
                    long allMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tAll0);
                    log.debug("WS rotate: session={} model={} final={} az={} el={} bytes={} metaMs={} renderMs={} sendMs={} totalMs={}",
                            session.getId(), modelId, finalFrame, azimuth, elevation, jpeg.length, metaMs, renderMs, sendMs, allMs);
                }
            }
        } catch (Exception e) {
            log.error("WebSocket message handling error", e);
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR);
                }
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error: {}", session.getId(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        log.debug("WebSocket closed: {} - {}", session.getId(), closeStatus);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
