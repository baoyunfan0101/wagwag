package com.wagwag.api.walk;

import com.wagwag.api.walk.TerritoryService.LeaderboardEntry;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class TerritoryLeaderboardCache {
    private static final String KEY = "wagwag:territory:leaderboard";
    private static final Duration TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;

    public TerritoryLeaderboardCache(StringRedisTemplate redis, ObjectMapper json,
                                     @Value("${app.territory-leaderboard-cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
    }

    public List<LeaderboardEntry> top(Supplier<List<LeaderboardEntry>> database) {
        if (enabled) {
            try {
                String cached = redis.opsForValue().get(KEY);
                if (cached != null) return Arrays.asList(json.readValue(cached, LeaderboardEntry[].class));
            } catch (DataAccessException | JacksonException ignored) { }
        }
        List<LeaderboardEntry> entries = database.get();
        if (enabled) {
            try { redis.opsForValue().set(KEY, json.writeValueAsString(entries), TTL); }
            catch (DataAccessException | JacksonException ignored) { }
        }
        return entries;
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
