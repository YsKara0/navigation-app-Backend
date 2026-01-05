package com.navigation.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.navigation.backend.handler.NavigationWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NavigationWebSocketHandler navigationWebSocketHandler;

    public WebSocketConfig(NavigationWebSocketHandler navigationWebSocketHandler) {
        this.navigationWebSocketHandler = navigationWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ws://localhost:8080/ws/navigation adresinden erisilecek
        registry.addHandler(navigationWebSocketHandler, "/ws/navigation")
                .setAllowedOriginPatterns("*"); // Flutter raw WebSocket için - SockJS yok
    }
}
