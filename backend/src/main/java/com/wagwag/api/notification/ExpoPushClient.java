package com.wagwag.api.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExpoPushClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String accessToken;

    public ExpoPushClient(ObjectMapper json, @Value("${app.push.base-url}") String baseUrl,
                          @Value("${app.push.access-token:}") String accessToken) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.accessToken = accessToken;
    }

    public Result send(String token, String type, long targetId, long notificationId) {
        return request("/send", List.of(Map.of("to", token, "title", "WagWag", "body",
            "MESSAGE".equals(type) ? "You have a new message." : "A pet-care task was updated.",
            "sound", "default", "channelId", "messages", "data",
            Map.of("type", type, "targetId", targetId, "notificationId", notificationId))), null);
    }

    public Result receipt(String ticketId) { return request("/getReceipts", Map.of("ids", List.of(ticketId)), ticketId); }

    private Result request(String path, Object payload, String ticketId) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            if (!accessToken.isBlank()) request.header("Authorization", "Bearer " + accessToken);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                return new Result(State.RETRY, null, "Http" + response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Result(State.FAILED, null, "Http" + response.statusCode());
            }
            JsonNode data = json.readTree(response.body()).path("data");
            JsonNode result = ticketId == null ? data.path(0) : data.path(ticketId);
            if (ticketId != null && result.isMissingNode()) return new Result(State.RETRY, null, "ReceiptUnavailable");
            if ("error".equals(result.path("status").asString())) {
                String error = result.path("details").path("error").asString("UnknownError");
                return new Result("MessageRateExceeded".equals(error) ? State.RETRY : State.FAILED, null, error);
            }
            if (!"ok".equals(result.path("status").asString())) return new Result(State.RETRY, null, "InvalidResponse");
            if (ticketId != null) return new Result(State.COMPLETE, null, null);
            String ticket = result.path("id").asString("");
            return ticket.isBlank() ? new Result(State.RETRY, null, "MissingTicket") : new Result(State.TICKET, ticket, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(State.RETRY, null, "Interrupted");
        } catch (IOException | JacksonException error) {
            return new Result(State.RETRY, null, "NetworkOrResponseError");
        }
    }

    public enum State { TICKET, COMPLETE, RETRY, FAILED }
    public record Result(State state, String ticketId, String error) {}
}
