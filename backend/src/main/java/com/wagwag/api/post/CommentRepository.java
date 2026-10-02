package com.wagwag.api.post;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    long countByPost_Id(Long postId);
    List<Comment> findByPost_IdOrderByCreatedAtAscIdAsc(Long postId);
}
