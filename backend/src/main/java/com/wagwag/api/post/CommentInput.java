package com.wagwag.api.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentInput(@NotBlank @Size(max = 500) String body) {}
