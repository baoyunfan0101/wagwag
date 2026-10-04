package com.wagwag.api.storage;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvatarStorage {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private final S3Objects objects;

    public AvatarStorage(S3Objects objects) { this.objects = objects; }

    public UploadTicket prepare(long petId, String contentType) {
        objects.requireConfigured();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use JPEG, PNG, or WebP");
        }
        String suffix = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String key = "pets/" + petId + "/" + UUID.randomUUID() + "." + suffix;
        var ticket = objects.prepare(key, contentType, false);
        return new UploadTicket(ticket.key(), ticket.uploadUrl(), ticket.publicUrl());
    }

    public String verifyAndGetUrl(long petId, String key) {
        objects.requireConfigured();
        if (key == null || !key.matches("pets/" + petId + "/[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid avatar key");
        }
        var object = objects.head(key);
        if (object.contentLength() == null || object.contentLength() == 0
                || object.contentLength() > MAX_BYTES || object.contentType() == null
                || !IMAGE_TYPES.contains(object.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be an image under 5 MB");
        }
        return objects.publicUrl(key);
    }

    public record UploadTicket(String key, String uploadUrl, String publicUrl) {}
}
