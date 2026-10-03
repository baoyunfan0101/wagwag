package com.wagwag.api.social;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SocialRestrictions {
    private final JdbcTemplate jdbc;

    public SocialRestrictions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean blockedEitherWay(long first, long second) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pet_blocks "
            + "WHERE (blocker_pet_id = ? AND blocked_pet_id = ?) "
            + "OR (blocker_pet_id = ? AND blocked_pet_id = ?))", Boolean.class,
            first, second, second, first));
    }

    public boolean blockedBy(long actor, long target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pet_blocks "
            + "WHERE blocker_pet_id = ? AND blocked_pet_id = ?)", Boolean.class, actor, target));
    }

    public boolean mutedBy(long actor, long target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pet_mutes "
            + "WHERE muter_pet_id = ? AND muted_pet_id = ?)", Boolean.class, actor, target));
    }

    public void block(long actor, long target) {
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, ?) "
            + "ON CONFLICT DO NOTHING", actor, target);
    }

    public void unblock(long actor, long target) {
        jdbc.update("DELETE FROM pet_blocks WHERE blocker_pet_id = ? AND blocked_pet_id = ?",
            actor, target);
    }

    public void mute(long actor, long target) {
        jdbc.update("INSERT INTO pet_mutes (muter_pet_id, muted_pet_id) VALUES (?, ?) "
            + "ON CONFLICT DO NOTHING", actor, target);
    }

    public void unmute(long actor, long target) {
        jdbc.update("DELETE FROM pet_mutes WHERE muter_pet_id = ? AND muted_pet_id = ?",
            actor, target);
    }

    public List<Long> blockedPetIds(long actor) {
        return jdbc.queryForList("SELECT blocked_pet_id AS id FROM pet_blocks WHERE blocker_pet_id = ? "
            + "UNION SELECT blocker_pet_id FROM pet_blocks WHERE blocked_pet_id = ?", Long.class,
            actor, actor);
    }

    public List<Long> hiddenFeedPetIds(long actor) {
        return jdbc.queryForList("SELECT blocked_pet_id AS id FROM pet_blocks WHERE blocker_pet_id = ? "
            + "UNION SELECT blocker_pet_id FROM pet_blocks WHERE blocked_pet_id = ? "
            + "UNION SELECT muted_pet_id FROM pet_mutes WHERE muter_pet_id = ?", Long.class,
            actor, actor, actor);
    }
}
