package com.wagwag.api.post;

import jakarta.validation.constraints.Size;

public record PostInput(
    @Size(max = 2000) String body,
    @Size(max = 2048) String imageUrl
) {}
