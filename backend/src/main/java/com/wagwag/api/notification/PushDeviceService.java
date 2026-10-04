package com.wagwag.api.notification;

import com.wagwag.api.pet.PetRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PushDeviceService {
    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final long devPetId;
    private final boolean pushEnabled;

    public PushDeviceService(JdbcTemplate jdbc, PetRepository pets, @Value("${app.dev-pet-id:0}") long devPetId,
                             @Value("${app.push.enabled:false}") boolean pushEnabled) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.devPetId = devPetId;
        this.pushEnabled = pushEnabled;
    }

    @Transactional
    public DeviceStatus register(UUID id, PushDeviceInput input) {
        long actor = actorId();
        try {
            List<UUID> saved = jdbc.queryForList("INSERT INTO push_devices (id, pet_id, expo_push_token, platform) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET expo_push_token = EXCLUDED.expo_push_token, "
                + "pet_id = EXCLUDED.pet_id, platform = EXCLUDED.platform, enabled = TRUE, updated_at = CURRENT_TIMESTAMP "
                + "WHERE push_devices.pet_id = EXCLUDED.pet_id OR NOT push_devices.enabled RETURNING id", UUID.class,
                id, actor, input.expoPushToken(), input.platform().name());
            if (saved.isEmpty()) throw conflict();
        } catch (DataIntegrityViolationException error) { throw conflict(); }
        return new DeviceStatus(true, pushEnabled);
    }

    @Transactional(readOnly = true)
    public DeviceStatus status(UUID id) {
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS "
            + "(SELECT 1 FROM push_devices WHERE id = ? AND pet_id = ? AND enabled)", Boolean.class, id, actorId()));
        return new DeviceStatus(enabled, pushEnabled);
    }

    @Transactional
    public void disable(UUID id) {
        jdbc.update("UPDATE push_devices SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND pet_id = ?",
            id, actorId());
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Device is registered to another pet; disable it there first");
    }

    public record DeviceStatus(boolean registered, boolean serverEnabled) {}
}
