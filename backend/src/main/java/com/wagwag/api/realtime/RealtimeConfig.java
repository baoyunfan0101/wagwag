package com.wagwag.api.realtime;

import com.wagwag.api.pet.PetRepository;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
@EnableScheduling
public class RealtimeConfig implements WebSocketConfigurer {
    private final RealtimeSocketHandler sockets;
    private final PetRepository pets;
    private final long devPetId;
    private final String allowedOrigins;

    public RealtimeConfig(RealtimeSocketHandler sockets, PetRepository pets,
                          @Value("${app.dev-pet-id:0}") long devPetId,
                          @Value("${app.allowed-origins}") String allowedOrigins) {
        this.sockets = sockets;
        this.pets = pets;
        this.devPetId = devPetId;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sockets, "/api/events").setAllowedOrigins(allowedOrigins.split(","))
            .addInterceptors(new HandshakeInterceptor() {
                @Override
                public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler handler, Map<String, Object> attributes) {
                    // The same server-configured development identity as REST; no client-selected pet/subscriptions.
                    if (request.getURI().getRawQuery() != null) {
                        response.setStatusCode(HttpStatus.BAD_REQUEST);
                        return false;
                    }
                    if (devPetId < 1 || !pets.existsById(devPetId)) {
                        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return false;
                    }
                    attributes.put("petId", devPetId);
                    return true;
                }

                @Override
                public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler handler, Exception error) { }
            });
    }
}
