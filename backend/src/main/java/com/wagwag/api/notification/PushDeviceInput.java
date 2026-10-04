package com.wagwag.api.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PushDeviceInput(@NotNull @Size(max = 255)
                              @Pattern(regexp = "(?:Expo|Exponent)PushToken\\[[A-Za-z0-9_-]+\\]") String expoPushToken,
                              @NotNull Platform platform) {
    public enum Platform { IOS, ANDROID }
}
