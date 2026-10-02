package com.wagwag.api.social;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FollowCache {
    public enum Relation { NONE, REQUESTED, FOLLOWING }

    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public FollowCache(StringRedisTemplate redis,
                       @Value("${app.follow-cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public long followerCount(long petId, LongSupplier database) {
        return count("wagwag:social:followers:" + petId, database);
    }

    public long followingCount(long petId, LongSupplier database) {
        return count("wagwag:social:following:" + petId, database);
    }

    public Relation relation(long followerId, long followingId, Supplier<Relation> database) {
        String key = relationKey(followerId, followingId);
        String cached = get(key);
        if (cached != null) {
            try { return Relation.valueOf(cached); }
            catch (IllegalArgumentException ignored) { }
        }
        Relation value = database.get();
        put(key, value.name());
        return value;
    }

    public void evictAfterCommit(long followerId, long followingId) {
        if (!enabled) return;
        Runnable evict = () -> {
            try {
                redis.delete(List.of(relationKey(followerId, followingId),
                    "wagwag:social:followers:" + followingId,
                    "wagwag:social:following:" + followerId));
            } catch (DataAccessException ignored) { }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else {
            evict.run();
        }
    }

    private long count(String key, LongSupplier database) {
        String cached = get(key);
        if (cached != null) {
            try { return Long.parseLong(cached); }
            catch (NumberFormatException ignored) { }
        }
        long value = database.getAsLong();
        put(key, Long.toString(value));
        return value;
    }

    private String get(String key) {
        if (!enabled) return null;
        try { return redis.opsForValue().get(key); }
        catch (DataAccessException ignored) { return null; }
    }

    private void put(String key, String value) {
        if (!enabled) return;
        try { redis.opsForValue().set(key, value, TTL); }
        catch (DataAccessException ignored) { }
    }

    private static String relationKey(long followerId, long followingId) {
        return "wagwag:social:relation:" + followerId + ":" + followingId;
    }
}
