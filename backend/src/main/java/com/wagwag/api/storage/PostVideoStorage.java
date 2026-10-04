package com.wagwag.api.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostVideoStorage {
    static final long MAX_BYTES = 50 * 1024 * 1024;
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
    private final S3Objects objects;
    private final JdbcTemplate jdbc;
    private final String mediaBaseUrl;

    public PostVideoStorage(S3Objects objects, JdbcTemplate jdbc,
                            @Value("${app.video.media-base-url:}") String mediaBaseUrl) {
        this.objects = objects;
        this.jdbc = jdbc;
        this.mediaBaseUrl = mediaBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public VideoTicket prepare(long petId, String contentType) {
        objects.requireConfigured();
        if (!VIDEO_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use MP4, MOV, or WebM");
        }
        String suffix = switch (contentType) {
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            default -> "mp4";
        };
        UUID id = UUID.randomUUID();
        String key = "pets/" + petId + "/posts/videos/" + id + "." + suffix;
        var ticket = objects.prepare(key, contentType, true);
        jdbc.update("INSERT INTO video_uploads (id, pet_id, source_key, content_type) VALUES (?, ?, ?, ?)",
            id, petId, key, contentType);
        return ticket(id, ticket);
    }

    @Transactional
    public VideoTicket renew(long petId, UUID id) {
        Upload upload = owned(petId, id, true);
        if (!"UPLOADING".equals(upload.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Video upload already completed");
        }
        return ticket(id, objects.prepare(upload.key(), upload.contentType(), true));
    }

    @Transactional(readOnly = true)
    public VideoState get(long petId, UUID id) { return state(owned(petId, id, false)); }

    @Transactional
    public VideoState complete(long petId, UUID id) {
        Upload upload = owned(petId, id, true);
        if ("UPLOADING".equals(upload.status())) {
            verifySource(upload);
            jdbc.update("UPDATE video_uploads SET status = 'QUEUED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
        return state(owned(petId, id, false));
    }

    @Transactional
    public VideoState retry(long petId, UUID id) {
        Upload upload = owned(petId, id, true);
        if ("FAILED".equals(upload.status())) {
            if ("INVALID_VIDEO".equals(upload.error()) || "VIDEO_TOO_LARGE".equals(upload.error())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another video; this file cannot be processed");
            }
            jdbc.update("UPDATE video_uploads SET status = 'QUEUED', attempts = 0, error = NULL, processing_token = NULL, "
                + "lease_until = NULL, next_attempt_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
        return state(owned(petId, id, false));
    }

    public VideoAsset verifyAndGetAsset(long petId, String key) {
        if (key == null || !key.matches("pets/" + petId + "/posts/videos/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(mp4|mov|webm)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid post video key");
        }
        List<UUID> ids = jdbc.query("SELECT id FROM video_uploads WHERE source_key = ? AND pet_id = ?",
            (rs, row) -> rs.getObject("id", UUID.class), key, petId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request and complete a video upload first");
        Upload upload = owned(petId, ids.getFirst(), false);
        // Publishing still verifies the uploaded source and immutable processed objects.
        verifySource(upload);
        if (!"READY".equals(upload.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for video processing before publishing");
        }
        var video = objects.head(upload.videoKey());
        var thumbnail = objects.head(upload.thumbnailKey());
        if (video.contentLength() == null || video.contentLength() <= 0 || video.contentLength() > MAX_BYTES
                || !"video/mp4".equals(video.contentType()) || thumbnail.contentLength() == null
                || thumbnail.contentLength() <= 0 || thumbnail.contentLength() > 512 * 1024
                || !"image/jpeg".equals(thumbnail.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid processed video");
        }
        return new VideoAsset(mediaUrl(upload.videoKey()), mediaUrl(upload.thumbnailKey()));
    }

    void verifySource(Upload upload) {
        var object = objects.head(upload.key());
        if (object.contentLength() == null || object.contentLength() <= 0 || object.contentLength() > MAX_BYTES
                || !upload.contentType().equals(object.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video must match its ticket and be at most 50 MB");
        }
    }

    private Upload owned(long petId, UUID id, boolean lock) {
        List<Upload> uploads = jdbc.query("SELECT * FROM video_uploads WHERE id = ? AND pet_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Upload(id, rs.getString("source_key"), rs.getString("content_type"), rs.getString("status"),
                rs.getString("video_key"), rs.getString("thumbnail_key"), rs.getString("error")), id, petId);
        if (uploads.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video upload not found");
        return uploads.getFirst();
    }

    private VideoState state(Upload upload) {
        boolean ready = "READY".equals(upload.status());
        return new VideoState(upload.id(), upload.key(), upload.status(), upload.error(),
            ready ? mediaUrl(upload.videoKey()) : null, ready ? mediaUrl(upload.thumbnailKey()) : null);
    }

    private String mediaUrl(String key) { return mediaBaseUrl.isBlank() ? objects.publicUrl(key) : mediaBaseUrl + "/" + key; }
    private VideoTicket ticket(UUID id, S3Objects.UploadTicket ticket) {
        return new VideoTicket(id, ticket.key(), ticket.uploadUrl(), ticket.headers());
    }

    record Upload(UUID id, String key, String contentType, String status, String videoKey, String thumbnailKey, String error) {}
    public record VideoTicket(UUID id, String key, String uploadUrl, Map<String, String> headers) {}
    public record VideoState(UUID id, String key, String status, String error, String videoUrl, String thumbnailUrl) {}
    public record VideoAsset(String videoUrl, String thumbnailUrl) {}
}
