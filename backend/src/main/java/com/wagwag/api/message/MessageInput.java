package com.wagwag.api.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record MessageInput(@NotNull UUID clientMessageId, @NotBlank @Size(max = 2000) String body) {}
