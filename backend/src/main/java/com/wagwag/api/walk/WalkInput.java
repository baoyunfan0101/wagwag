package com.wagwag.api.walk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record WalkInput(@NotNull Instant startedAt, @NotNull Instant endedAt,
                        @NotEmpty @Size(max = 2000) List<@NotNull @Valid PointInput> points) {
    public record PointInput(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                             @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                             @NotNull Instant recordedAt) {}
}
