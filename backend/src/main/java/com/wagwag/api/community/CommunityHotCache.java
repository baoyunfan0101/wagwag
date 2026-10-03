package com.wagwag.api.community;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CommunityHotCache {
    private static final String KEY = "wagwag:community:hot-ids";
    private static final Duration TTL = Duration.ofMinutes(1);
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public CommunityHotCache(StringRedisTemplate redis,
                             @Value("${app.community-hot-cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public List<Long> topIds(Supplier<List<Long>> database) {
        if (enabled) {
            try {
                String cached = redis.opsForValue().get(KEY);
                if (cached != null) {
                    if (cached.equals("-")) return List.of();
                    return Arrays.stream(cached.split(",")).map(Long::parseLong).toList();
                }
            } catch (DataAccessException | NumberFormatException ignored) { }
        }
        List<Long> ids = database.get();
        if (enabled) {
            try {
                redis.opsForValue().set(KEY, ids.isEmpty() ? "-" : ids.stream()
                    .map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-"), TTL);
            } catch (DataAccessException ignored) { }
        }
        return ids;
    }

    public void evictAfterCommit() {
        if (!enabled) return;
        Runnable evict = () -> {
            try { redis.delete(KEY); }
            catch (DataAccessException ignored) { }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else {
            evict.run();
        }
    }
}
