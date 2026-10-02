package com.wagwag.api.post;

import java.time.Instant;

public record PostResponse(
    Long id, Long petId, String petName, String petAvatarUrl, String body,
    String imageUrl, Instant createdAt, long likeCount, long commentCount, boolean likedByMe
) {}
