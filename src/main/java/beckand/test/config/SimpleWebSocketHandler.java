package beckand.test.config;

import beckand.test.DTO.*;
import beckand.test.Service.FileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleWebSocketHandler extends TextWebSocketHandler {

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("WebSocket connection established: {} (uri: {})", session.getId(), session.getUri());
        
        // Отправляем приветственное сообщение
        try {
            ConnectedMessage connected = new ConnectedMessage();
            connected.setType("CONNECTED");
            sendMessage(session, connected);
            log.debug("Sent CONNECTED message to session: {}", session.getId());
        } catch (Exception e) {
            log.error("Error sending CONNECTED message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        log.info("WebSocket connection closed: {} (code: {}, reason: {})", session.getId(), status.getCode(), status.getReason());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String payload = message.getPayload();
            log.info("Received WebSocket message from session {}: {} bytes", session.getId(), payload.length());
            log.debug("Message content (first 500 chars): {}", payload.substring(0, Math.min(500, payload.length())));
            
            // Сначала читаем как Map, чтобы получить тип
            var jsonNode = objectMapper.readTree(payload);
            String messageType = jsonNode.has("type") ? jsonNode.get("type").asText() : null;
            log.info("Message type from JSON: {} from session: {}", messageType, session.getId());
            
            if (messageType == null || messageType.isEmpty()) {
                log.warn("Message has no type field, payload: {}", payload);
                ErrorResponse error = new ErrorResponse();
                error.setType("ERROR");
                error.setMessage("Message must have a 'type' field");
                error.setErrorCode("INVALID_MESSAGE");
                sendMessage(session, error);
                return;
            }
            
            // Десериализуем в конкретный тип в зависимости от messageType
            if ("MODEL_LIST_REQUEST".equals(messageType)) {
                log.info("Processing MODEL_LIST_REQUEST");
                ModelListRequest request = objectMapper.readValue(payload, ModelListRequest.class);
                handleModelListRequest(session, request);
            } else {
                log.warn("Unknown message type: {} from session: {}", messageType, session.getId());
                ErrorResponse error = new ErrorResponse();
                error.setType("ERROR");
                error.setMessage("Unknown message type: " + messageType);
                error.setErrorCode("UNKNOWN_TYPE");
                sendMessage(session, error);
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message from session: " + session.getId(), e);
            log.error("Message payload that caused error: " + message.getPayload().substring(0, Math.min(500, message.getPayload().length())));
            ErrorResponse error = new ErrorResponse();
            error.setType("ERROR");
            error.setMessage("Error processing request: " + e.getMessage());
            error.setErrorCode("PROCESSING_ERROR");
            sendMessage(session, error);
        }
    }

    private void handleModelListRequest(WebSocketSession session, ModelListRequest request) {
        String requestId = request.getRequestId() != null && !request.getRequestId().isEmpty()
            ? request.getRequestId()
            : UUID.randomUUID().toString();
        
        log.info("Handling MODEL_LIST_REQUEST from session {} with requestId: {}", session.getId(), requestId);
        
        try {
            log.info("Getting file list from FileService...");
            var models = fileService.getAllFiles();
            log.info("Retrieved {} models from FileService", models.size());
            
            ModelListResponse response = new ModelListResponse();
            response.setType("MODEL_LIST_RESPONSE");
            response.setRequestId(requestId);
            response.setModels(models);
            
            log.info("Sending MODEL_LIST_RESPONSE with {} models to session {}", models.size(), session.getId());
            sendMessage(session, response);
            log.info("MODEL_LIST_RESPONSE sent successfully");
        } catch (Exception e) {
            log.error("Error getting model list via WebSocket for session: " + session.getId(), e);
            ErrorResponse error = new ErrorResponse();
            error.setType("ERROR");
            error.setRequestId(requestId);
            error.setMessage("Error getting model list: " + e.getMessage());
            error.setErrorCode("LIST_ERROR");
            sendMessage(session, error);
        }
    }

    private void sendMessage(WebSocketSession session, Object message) {
        try {
            if (!session.isOpen()) {
                log.warn("Cannot send message: session {} is not open", session.getId());
                return;
            }
            String json = objectMapper.writeValueAsString(message);
            String messageType = message instanceof WebSocketMessage ? ((WebSocketMessage) message).getType() : "unknown";
            int messageSize = json.length();
            
            log.info("Sending message to session {}: type={}, size={} bytes ({} KB)", 
                session.getId(), messageType, messageSize, messageSize / 1024);
            
            // Для больших сообщений логируем предупреждение
            if (messageSize > 1024 * 1024) { // > 1MB
                log.warn("Large message size: {} KB, this may cause issues", messageSize / 1024);
            }
            
            session.sendMessage(new TextMessage(json));
            log.debug("Message sent successfully to session {}", session.getId());
        } catch (Exception e) {
            log.error("Error sending WebSocket message to session: " + session.getId(), e);
            // Не пробрасываем исключение, так как оно уже обработано и залогировано
        }
    }
}
