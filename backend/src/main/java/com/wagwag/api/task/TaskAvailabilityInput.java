package com.wagwag.api.task;

import jakarta.validation.constraints.NotNull;

public record TaskAvailabilityInput(@NotNull Boolean acceptingTasks) {}
