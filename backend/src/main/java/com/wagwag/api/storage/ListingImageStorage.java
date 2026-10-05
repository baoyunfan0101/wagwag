package com.wagwag.api.storage;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ListingImageStorage {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private final S3Objects objects;

    public ListingImageStorage(S3Objects objects) { this.objects = objects; }

    public S3Objects.UploadTicket prepare(long petId, String contentType) {
        objects.requireConfigured();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use JPEG, PNG, or WebP");
        }
        String suffix = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String key = "pets/" + petId + "/listings/" + UUID.randomUUID() + "." + suffix;
        return objects.prepare(key, contentType, true);
    }

    public String verifyAndGetUrl(long petId, String key) {
        objects.requireConfigured();
        if (key == null || !key.matches("pets/" + petId + "/listings/[0-9a-f]{8}-[0-9a-f]{4}-"
                + "[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listing image key");
        }
        var object = objects.head(key);
        if (object.contentLength() == null || object.contentLength() == 0
                || object.contentLength() > MAX_BYTES || object.contentType() == null
                || !IMAGE_TYPES.contains(object.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Listing image must be under 5 MB");
        }
        return objects.publicUrl(key);
    }
}
