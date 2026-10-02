package com.wagwag.api.post;

import jakarta.validation.constraints.Size;
import java.util.List;

public record PostInput(
    @Size(max = 2000) String body,
    @Size(max = 2048) String imageUrl,
    @Size(max = 4) List<String> imageKeys
) {}
