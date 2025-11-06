package beckand.test.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RenderWebSocketHandler renderWebSocketHandler;

    public WebSocketConfig(RenderWebSocketHandler renderWebSocketHandler) {
        this.renderWebSocketHandler = renderWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(renderWebSocketHandler, "/ws/render/{modelId}")
                .setAllowedOriginPatterns("*");
    }
}


