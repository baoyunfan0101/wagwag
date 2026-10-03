package com.wagwag.api.social;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SocialPairLock {
    private final JdbcTemplate jdbc;

    public SocialPairLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(long firstPetId, long secondPetId) {
        lockPet(Math.min(firstPetId, secondPetId));
        if (firstPetId != secondPetId) lockPet(Math.max(firstPetId, secondPetId));
    }

    public void lockPet(long petId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Social pet locks require a transaction");
        }
        // Lock rows in ID order for pairs; privacy changes lock their one target row.
        jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR UPDATE", Long.class, petId);
    }
}
