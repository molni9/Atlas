package beckand.test.config;

import beckand.test.websocket.RenderWebSocketHandler;
import beckand.test.websocket.RenderControlWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RenderWebSocketHandler renderWebSocketHandler;
    private final RenderControlWebSocketHandler renderControlWebSocketHandler;

    @Value("${spring.web.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(renderWebSocketHandler, "/ws/render")
                .setAllowedOriginPatterns(resolveOrigins());
        registry.addHandler(renderControlWebSocketHandler, "/ws/render-control")
                .setAllowedOriginPatterns(resolveOrigins());
    }

    private String[] resolveOrigins() {
        if ("*".equals(allowedOrigins)) {
            return new String[]{"*"};
        }
        return allowedOrigins.replace(" ", "").split(",");
    }
}

