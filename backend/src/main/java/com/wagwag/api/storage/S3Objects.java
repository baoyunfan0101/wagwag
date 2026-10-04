package com.wagwag.api.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3Objects {
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicBaseUrl;
    private final String accessKey;
    private final String secretKey;

    public S3Objects(@Value("${app.storage.bucket:}") String bucket,
                     @Value("${app.storage.region:us-east-1}") String region,
                     @Value("${app.storage.endpoint:}") String endpoint,
                     @Value("${app.storage.public-base-url:}") String publicBaseUrl,
                     @Value("${app.storage.access-key:}") String accessKey,
                     @Value("${app.storage.secret-key:}") String secretKey) {
        this.bucket = bucket;
        this.region = region;
        this.endpoint = endpoint;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    void requireConfigured() {
        if (bucket.isBlank() || publicBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Object storage is not configured");
        }
        if (accessKey.isBlank() != secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Both storage credentials are required");
        }
    }

    UploadTicket prepare(String key, String contentType, boolean createOnly) {
        requireConfigured();
        var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (createOnly) put.ifNoneMatch("*");
        var builder = S3Presigner.builder().region(Region.of(region)).serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        try (S3Presigner presigner = builder.build()) {
            String uploadUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)).putObjectRequest(put.build()).build()).url().toString();
            Map<String, String> headers = createOnly
                ? Map.of("Content-Type", contentType, "If-None-Match", "*") : Map.of("Content-Type", contentType);
            return new UploadTicket(key, uploadUrl, publicUrl(key), headers);
        } catch (SdkClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage credentials are unavailable", error);
        }
    }

    HeadObjectResponse head(String key) {
        requireConfigured();
        try (S3Client client = client()) {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the object before saving", error);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage is unavailable", error);
        } catch (SdkClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage is unavailable", error);
        }
    }

    String publicUrl(String key) { return publicBaseUrl + "/" + key; }

    void download(String key, Path target, long maxBytes) throws IOException {
        requireConfigured();
        try (S3Client client = client(); var stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
             var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Video exceeds size limit");
                output.write(buffer, 0, read);
            }
        }
    }

    void putImmutable(String key, Path source, String contentType) throws IOException, InterruptedException {
        // Use the same signed create-only PUT protocol as client uploads. This also works
        // with the local unauthenticated SeaweedFS gateway (SDK streaming PUT does not).
        UploadTicket ticket = prepare(key, contentType, true);
        var request = HttpRequest.newBuilder(URI.create(ticket.uploadUrl())).timeout(Duration.ofSeconds(60))
            .header("Cache-Control", "public, max-age=31536000, immutable");
        ticket.headers().forEach(request::header);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request.PUT(HttpRequest.BodyPublishers.ofFile(source)).build(),
                HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Processed media upload failed");
        }
    }

    private S3Client client() {
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(true)
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    public record UploadTicket(String key, String uploadUrl, String publicUrl, Map<String, String> headers) {}
}
