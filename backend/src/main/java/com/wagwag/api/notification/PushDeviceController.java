package com.wagwag.api.notification;

import com.wagwag.api.notification.PushDeviceService.DeviceStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/push-devices")
public class PushDeviceController {
    private final PushDeviceService devices;

    public PushDeviceController(PushDeviceService devices) { this.devices = devices; }

    @PutMapping("/{id}")
    public DeviceStatus register(@PathVariable UUID id, @Valid @RequestBody PushDeviceInput input) {
        return devices.register(id, input);
    }

    @GetMapping("/{id}")
    public DeviceStatus status(@PathVariable UUID id) { return devices.status(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id) { devices.disable(id); }
}
