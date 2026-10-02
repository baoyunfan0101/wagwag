package com.wagwag.api.post;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    long countByPost_Id(Long postId);
    boolean existsByPost_IdAndPet_Id(Long postId, Long petId);
    long deleteByPost_IdAndPet_Id(Long postId, Long petId);
}
