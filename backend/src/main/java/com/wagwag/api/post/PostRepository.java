package com.wagwag.api.post;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface PostRepository extends JpaRepository<Post, Long> {
    @Query("select p from Post p join fetch p.pet order by p.createdAt desc, p.id desc")
    List<Post> findFeed(Pageable page);

    @Query("select p from Post p join fetch p.pet where p.createdAt < :createdAt "
        + "or (p.createdAt = :createdAt and p.id < :id) order by p.createdAt desc, p.id desc")
    List<Post> findFeedAfter(Instant createdAt, long id, Pageable page);
}
