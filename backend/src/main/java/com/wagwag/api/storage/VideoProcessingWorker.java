package com.wagwag.api.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class VideoProcessingWorker {
    private static final Logger log = LoggerFactory.getLogger(VideoProcessingWorker.class);
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final S3Objects objects;
    private final PostVideoStorage storage;
    private final FfmpegVideoProcessor processor;
    private final boolean enabled;

    public VideoProcessingWorker(JdbcTemplate jdbc, TransactionTemplate transactions, S3Objects objects,
                                  PostVideoStorage storage, FfmpegVideoProcessor processor,
                                  @Value("${app.video.processing-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objects = objects;
        this.storage = storage;
        this.processor = processor;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void scheduled() { if (enabled) processNext(); }

    public boolean processNext() {
        Job job = transactions.execute(tx -> claim());
        if (job == null) return false;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("wagwag-video-");
            Path source = directory.resolve("source");
            Path video = directory.resolve("video.mp4");
            Path thumbnail = directory.resolve("thumbnail.jpg");
            storage.verifySource(new PostVideoStorage.Upload(job.id(), job.key(), job.contentType(), "PROCESSING", null, null, null));
            objects.download(job.key(), source, PostVideoStorage.MAX_BYTES);
            processor.process(source, video, thumbnail);
            // Each attempt gets immutable output keys. A stale worker cannot replace a successful result.
            String prefix = "pets/" + job.petId() + "/posts/processed/" + job.id() + "/" + job.token();
            String videoKey = prefix + ".mp4";
            String thumbnailKey = prefix + ".jpg";
            objects.putImmutable(videoKey, video, "video/mp4");
            objects.putImmutable(thumbnailKey, thumbnail, "image/jpeg");
            jdbc.update("UPDATE video_uploads SET status = 'READY', video_key = ?, thumbnail_key = ?, error = NULL, "
                + "lease_until = NULL, processing_token = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?", videoKey, thumbnailKey, job.id(), job.token());
        } catch (FfmpegVideoProcessor.ProcessingFailure error) {
            log.warn("Video processor returned {} for {}", error.code, job.id());
            failed(job, error.code, error.retryable);
        } catch (Exception error) {
            log.warn("Video processing failed for {}", job.id(), error);
            // Raw provider/processor output is not exposed to clients.
            failed(job, "STORAGE_OR_PROCESSING_UNAVAILABLE", true);
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException error) { log.warn("Could not remove video temporary directory for {}", job.id()); }
            }
        }
        return true;
    }

    private Job claim() {
        UUID token = UUID.randomUUID();
        List<Job> jobs = jdbc.query("WITH next AS (SELECT id FROM video_uploads WHERE "
            + "(status = 'QUEUED' AND next_attempt_at <= CURRENT_TIMESTAMP) OR "
            + "(status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP) "
            + "ORDER BY next_attempt_at, created_at LIMIT 1 FOR UPDATE SKIP LOCKED) "
            + "UPDATE video_uploads v SET status = 'PROCESSING', attempts = attempts + 1, processing_token = ?, "
            + "lease_until = CURRENT_TIMESTAMP + INTERVAL '10 minutes', updated_at = CURRENT_TIMESTAMP "
            + "FROM next WHERE v.id = next.id RETURNING v.*",
            (rs, row) -> new Job(rs.getObject("id", UUID.class), rs.getLong("pet_id"), rs.getString("source_key"),
                rs.getString("content_type"), token, rs.getInt("attempts")), token);
        if (jobs.isEmpty()) return null;
        Job job = jobs.getFirst();
        if (job.attempts() > MAX_ATTEMPTS) {
            failed(job, "PROCESSING_INTERRUPTED", false);
            return null;
        }
        return job;
    }

    private void failed(Job job, String error, boolean retryable) {
        boolean retry = retryable && job.attempts() < MAX_ATTEMPTS;
        jdbc.update("UPDATE video_uploads SET status = ?, error = ?, processing_token = NULL, lease_until = NULL, "
            + "next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?",
            retry ? "QUEUED" : "FAILED", error, 10 * job.attempts(), job.id(), job.token());
    }

    private record Job(UUID id, long petId, String key, String contentType, UUID token, int attempts) {}
}
