package com.wagwag.api.notification;

import com.wagwag.api.notification.NotificationService.Notification;
import com.wagwag.api.notification.NotificationService.NotificationPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public NotificationPage list(@RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(required = false) Long beforeId) {
        return notifications.list(limit, beforeId);
    }

    @PutMapping("/{id}/read")
    public Notification read(@PathVariable long id) { return notifications.read(id); }
}
