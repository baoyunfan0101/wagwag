package com.wagwag.api.post;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostMediaRepository extends JpaRepository<PostMedia, Long> {
    Optional<PostMedia> findFirstByPost_IdOrderBySortOrderAsc(Long postId);
}
