package com.wagwag.api.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class PostImageStorage {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicBaseUrl;
    private final String accessKey;
    private final String secretKey;

    public PostImageStorage(@Value("${app.storage.bucket:}") String bucket,
                            @Value("${app.storage.region:us-east-1}") String region,
                            @Value("${app.storage.endpoint:}") String endpoint,
                            @Value("${app.storage.public-base-url:}") String publicBaseUrl,
                            @Value("${app.storage.access-key:}") String accessKey,
                            @Value("${app.storage.secret-key:}") String secretKey) {
        this.bucket = bucket;
        this.region = region;
        this.endpoint = endpoint;
        this.publicBaseUrl = publicBaseUrl;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    public UploadTicket prepare(long petId, String contentType) {
        requireConfigured();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use JPEG, PNG, or WebP");
        }
        String suffix = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String key = "pets/" + petId + "/posts/" + UUID.randomUUID() + "." + suffix;
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket).key(key).contentType(contentType).build();
        try (S3Presigner presigner = presigner()) {
            String uploadUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)).putObjectRequest(put).build())
                .url().toString();
            return new UploadTicket(key, uploadUrl, publicUrl(key));
        } catch (SdkClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage credentials are unavailable", error);
        }
    }

    public String verifyAndGetUrl(long petId, String key) {
        requireConfigured();
        if (key == null || !key.matches("pets/" + petId + "/posts/[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid post image key");
        }
        try (S3Client client = client()) {
            var object = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            if (object.contentLength() == null || object.contentLength() == 0
                    || object.contentLength() > MAX_BYTES || object.contentType() == null
                    || !IMAGE_TYPES.contains(object.contentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Post image must be under 5 MB");
            }
            return publicUrl(key);
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the image before publishing", error);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage is unavailable", error);
        } catch (SdkClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage is unavailable", error);
        }
    }

    private void requireConfigured() {
        if (bucket.isBlank() || publicBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Post image storage is not configured");
        }
        if (accessKey.isBlank() != secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Both storage credentials are required");
        }
    }

    private String publicUrl(String key) {
        return publicBaseUrl.replaceAll("/+$", "") + "/" + key;
    }

    private S3Presigner presigner() {
        var builder = S3Presigner.builder().region(Region.of(region)).serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    private S3Client client() {
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(true);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    public record UploadTicket(String key, String uploadUrl, String publicUrl) {}
}
