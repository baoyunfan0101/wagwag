package com.wagwag.api.post;

import java.time.Instant;
import java.util.List;

public record PostResponse(
    Long id, Long petId, String petName, String petAvatarUrl,
    Long communityId, String communityName, String body,
    List<String> imageUrls, String videoUrl, String videoThumbnailUrl, Instant createdAt,
    long likeCount, long commentCount, boolean likedByMe
) {}
