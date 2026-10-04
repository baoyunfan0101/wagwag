package com.wagwag.api.message;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReceiptInput(@NotNull @Min(0) Long throughMessageId, @NotNull Boolean read) {}
