package com.wagwag.api.message;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConversationInput(@NotNull @Positive Long petId) {}
