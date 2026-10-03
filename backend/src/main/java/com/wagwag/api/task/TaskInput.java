package com.wagwag.api.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskInput(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 2000) String description,
    @NotNull Category category,
    @NotNull Double latitude,
    @NotNull Double longitude
) {
    public enum Category { DOG_WALKING, PET_SITTING, FEEDING, CHECK_IN }
}
