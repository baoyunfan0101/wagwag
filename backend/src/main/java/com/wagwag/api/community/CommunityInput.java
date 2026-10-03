package com.wagwag.api.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommunityInput(@NotBlank @Size(max = 80) String name,
                             @Size(max = 500) String description) {}
