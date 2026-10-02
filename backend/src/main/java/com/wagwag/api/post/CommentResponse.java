package com.wagwag.api.post;

import java.time.Instant;

public record CommentResponse(
    Long id, Long postId, Long petId, String petName, String petAvatarUrl,
    String body, Instant createdAt
) {
    public static CommentResponse from(Comment comment) {
        var pet = comment.getPet();
        return new CommentResponse(comment.getId(), comment.getPost().getId(), pet.getId(),
            pet.getName(), pet.getAvatarUrl(), comment.getBody(), comment.getCreatedAt());
    }
}
