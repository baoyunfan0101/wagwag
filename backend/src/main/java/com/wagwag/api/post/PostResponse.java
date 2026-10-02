package com.wagwag.api.post;

import java.time.Instant;
import java.util.List;

public record PostResponse(
    Long id, Long petId, String petName, String petAvatarUrl, String body,
    List<String> imageUrls, Instant createdAt,
    long likeCount, long commentCount, boolean likedByMe
) {}
