package com.wagwag.api.post;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    long countByPost_Id(Long postId);
    List<Comment> findByPost_IdOrderByCreatedAtAscIdAsc(Long postId);

    @Query("select c.post.id, count(c) from Comment c where c.post.id in :postIds group by c.post.id")
    List<Object[]> countByPostIds(Collection<Long> postIds);
}
