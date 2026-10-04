package com.wagwag.api.realtime;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class RealtimeBus implements DisposableBean {
    private static final String CHANNEL = "wagwag:realtime";
    private final String origin = UUID.randomUUID().toString();
    private final RealtimeSocketHandler sockets;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisMessageListenerContainer listener;
    private final boolean enabled;

    public RealtimeBus(RealtimeSocketHandler sockets, StringRedisTemplate redis, ObjectMapper json,
                       RedisConnectionFactory connections, @Value("${app.realtime.redis-enabled:false}") boolean enabled) {
        this.sockets = sockets;
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connections);
        listener.setMaxSubscriptionRegistrationWaitingTime(1000);
        listener.addMessageListener((message, pattern) -> {
            try {
                Envelope envelope = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), Envelope.class);
                if (!origin.equals(envelope.origin())) deliver(envelope.petId(), envelope.event());
            } catch (JacksonException ignored) { }
        }, new ChannelTopic(CHANNEL));
        listener.afterPropertiesSet();
    }

    @Scheduled(fixedDelay = 5000)
    public void connect() {
        if (enabled && !listener.isRunning()) {
            try { listener.start(); } catch (DataAccessException | IllegalStateException ignored) { }
        }
    }

    public void afterCommit(long petId, Event event) {
        Runnable publish = () -> {
            deliver(petId, event);
            if (enabled) {
                try { redis.convertAndSend(CHANNEL, json.writeValueAsString(new Envelope(origin, petId, event))); }
                catch (DataAccessException | JacksonException ignored) { }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
    }

    private void deliver(long petId, Event event) {
        try { sockets.send(petId, json.writeValueAsString(event)); }
        catch (JacksonException ignored) { }
    }

    @Override
    public void destroy() throws Exception { listener.destroy(); }

    public record Event(String type, Long conversationId, Long messageId, Long senderPetId) {}
    public record Envelope(String origin, long petId, Event event) {}
}
