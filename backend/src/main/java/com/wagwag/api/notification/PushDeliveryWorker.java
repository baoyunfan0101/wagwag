package com.wagwag.api.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.push.enabled", havingValue = "true")
public class PushDeliveryWorker {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ExpoPushClient expo;

    public PushDeliveryWorker(JdbcTemplate jdbc, TransactionTemplate transactions, ExpoPushClient expo) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.expo = expo;
    }

    @Scheduled(fixedDelay = 1000)
    public void runOnce() {
        for (int count = 0; count < 10; count++) {
            if (!Boolean.TRUE.equals(transactions.execute(tx -> processOne()))) break;
        }
    }

    private boolean processOne() {
        List<Delivery> rows = jdbc.query("SELECT d.notification_id, d.device_id, d.status, d.attempts, d.ticket_id, "
            + "CASE WHEN d.status = 'TICKET' THEN d.sent_token ELSE device.expo_push_token END AS expo_push_token, "
            + "device.enabled, device.pet_id = n.recipient_pet_id AS correct_owner, "
            + "n.read_at IS NOT NULL AS already_read, CASE WHEN n.message_id IS NOT NULL THEN 'MESSAGE' ELSE 'TASK_STATUS' END AS type, "
            + "COALESCE(m.conversation_id, e.task_id) AS target_id FROM push_deliveries d "
            + "JOIN push_devices device ON device.id = d.device_id JOIN notifications n ON n.id = d.notification_id "
            + "LEFT JOIN messages m ON m.id = n.message_id LEFT JOIN task_events e ON e.id = n.task_event_id "
            + "WHERE d.status IN ('PENDING', 'TICKET') AND d.next_attempt_at <= CURRENT_TIMESTAMP "
            + "ORDER BY d.next_attempt_at, d.notification_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED",
            (rs, row) -> new Delivery(rs.getLong("notification_id"), rs.getObject("device_id", UUID.class),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("ticket_id"), rs.getString("expo_push_token"),
                rs.getBoolean("enabled") && rs.getBoolean("correct_owner"), rs.getBoolean("already_read"),
                rs.getString("type"), rs.getLong("target_id")));
        if (rows.isEmpty()) return false;
        Delivery delivery = rows.getFirst();
        // Once Expo accepted a ticket, still check its receipt even if the notification was read later.
        if (!delivery.enabled() || ("PENDING".equals(delivery.status()) && delivery.alreadyRead())) {
            finish(delivery, "SKIPPED", null);
            return true;
        }
        ExpoPushClient.Result result = "TICKET".equals(delivery.status())
            ? expo.receipt(delivery.ticketId())
            : expo.send(delivery.token(), delivery.type(), delivery.targetId(), delivery.notificationId());
        switch (result.state()) {
            case TICKET -> jdbc.update("UPDATE push_deliveries SET status = 'TICKET', ticket_id = ?, sent_token = ?, attempts = 0, "
                + "last_error = NULL, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '15 minutes' "
                + "WHERE notification_id = ? AND device_id = ?", result.ticketId(), delivery.token(), delivery.notificationId(), delivery.deviceId());
            case COMPLETE -> finish(delivery, "COMPLETE", null);
            case FAILED -> {
                if ("DeviceNotRegistered".equals(result.error())) {
                    jdbc.update("UPDATE push_devices SET enabled = FALSE WHERE id = ? AND expo_push_token = ?",
                        delivery.deviceId(), delivery.token());
                }
                finish(delivery, "FAILED", result.error());
            }
            case RETRY -> {
                int attempts = delivery.attempts() + 1;
                if (attempts >= MAX_ATTEMPTS) finish(delivery, "FAILED", result.error());
                else jdbc.update("UPDATE push_deliveries SET attempts = ?, last_error = ?, "
                    + "next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second') "
                    + "WHERE notification_id = ? AND device_id = ?", attempts, shortError(result.error()),
                    "TICKET".equals(delivery.status()) ? 60 : 10 * (1 << attempts), delivery.notificationId(), delivery.deviceId());
            }
        }
        return true;
    }

    private void finish(Delivery delivery, String status, String error) {
        jdbc.update("UPDATE push_deliveries SET status = ?, last_error = ? WHERE notification_id = ? AND device_id = ?",
            status, shortError(error), delivery.notificationId(), delivery.deviceId());
    }

    private static String shortError(String error) { return error == null ? null : error.substring(0, Math.min(error.length(), 120)); }

    private record Delivery(long notificationId, UUID deviceId, String status, int attempts, String ticketId,
                            String token, boolean enabled, boolean alreadyRead, String type, long targetId) {}
}
