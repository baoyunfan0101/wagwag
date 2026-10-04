package com.wagwag.api.storage;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostVideoStorage {
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
    private static final long MAX_BYTES = 50 * 1024 * 1024;
    private final S3Objects objects;

    public PostVideoStorage(S3Objects objects) { this.objects = objects; }

    public S3Objects.UploadTicket prepare(long petId, String contentType) {
        objects.requireConfigured();
        if (!VIDEO_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use MP4, MOV, or WebM");
        }
        String suffix = switch (contentType) {
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            default -> "mp4";
        };
        String key = "pets/" + petId + "/posts/videos/" + UUID.randomUUID() + "." + suffix;
        return objects.prepare(key, contentType, true);
    }

    public String verifyAndGetUrl(long petId, String key) {
        objects.requireConfigured();
        if (key == null || !key.matches("pets/" + petId + "/posts/videos/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(mp4|mov|webm)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid post video key");
        }
        var object = objects.head(key);
        if (object.contentLength() == null || object.contentLength() == 0
                || object.contentLength() > MAX_BYTES || object.contentType() == null
                || !VIDEO_TYPES.contains(object.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Post video must be at most 50 MB");
        }
        return objects.publicUrl(key);
    }
}
