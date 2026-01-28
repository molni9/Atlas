package beckand.test.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class SimpleWebSocketConfig implements WebSocketConfigurer {

    private final SimpleWebSocketHandler webSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Регистрируем простой WebSocket handler без STOMP
        registry.addHandler(webSocketHandler, "/ws-simple")
                .setAllowedOrigins("*");
    }
}
