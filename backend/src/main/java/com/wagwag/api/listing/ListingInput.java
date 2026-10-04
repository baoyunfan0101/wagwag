package com.wagwag.api.listing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ListingInput(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @Min(0) @Max(100000000) Long priceCents,
    @Size(max = 4) List<String> imageUrls
) {}
