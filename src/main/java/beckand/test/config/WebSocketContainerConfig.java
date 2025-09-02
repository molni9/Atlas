package beckand.test.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
public class WebSocketContainerConfig {

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024); // 10 MB
        container.setMaxTextMessageBufferSize(512 * 1024); // 512 KB
        container.setMaxSessionIdleTimeout(5 * 60 * 1000L); // 5 minutes
        return container;
    }
} 