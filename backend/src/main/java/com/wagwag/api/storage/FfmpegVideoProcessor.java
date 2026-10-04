package com.wagwag.api.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FfmpegVideoProcessor {
    private final String executable;

    public FfmpegVideoProcessor(@Value("${app.video.ffmpeg:ffmpeg}") String executable) { this.executable = executable; }

    public void process(Path source, Path video, Path thumbnail) {
        run(List.of("-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm", "-threads", "2", "-i", source.toString(),
            "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
            "-vf", "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p", "-r", "30", "-threads", "2",
            "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-movflags", "+faststart", video.toString()), source.getParent(), 120);
        run(List.of("-protocol_whitelist", "file", "-i", video.toString(), "-frames:v", "1", "-vf", "scale=320:-2",
            "-q:v", "3", "-update", "1", thumbnail.toString()), source.getParent(), 30);
        try {
            if (Files.size(video) == 0 || Files.size(video) > PostVideoStorage.MAX_BYTES
                    || Files.size(thumbnail) == 0 || Files.size(thumbnail) > 512 * 1024) {
                throw new ProcessingFailure("VIDEO_TOO_LARGE", false);
            }
        } catch (IOException error) { throw new ProcessingFailure("PROCESSING_FAILED", true); }
    }

    private void run(List<String> options, Path directory, long timeoutSeconds) {
        List<String> args = new ArrayList<>(List.of(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-max_alloc", "67108864", "-filter_threads", "1"));
        args.addAll(options);
        Process process = null;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true)
                .redirectOutput(directory.resolve("ffmpeg.log").toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new ProcessingFailure("PROCESSING_TIMEOUT", true);
            if (process.exitValue() != 0) throw new ProcessingFailure("INVALID_VIDEO", false);
        } catch (IOException error) {
            throw new ProcessingFailure("PROCESSOR_UNAVAILABLE", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProcessingFailure("PROCESSING_INTERRUPTED", true);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        }
    }

    public static class ProcessingFailure extends RuntimeException {
        final String code;
        final boolean retryable;
        public ProcessingFailure(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
    }
}
