package com.wagwag.api.post;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostMediaRepository extends JpaRepository<PostMedia, Long> {
    List<PostMedia> findByPost_IdOrderBySortOrderAsc(Long postId);

    @Query("select m.post.id, m.url from PostMedia m where m.post.id in :postIds "
        + "order by m.post.id, m.sortOrder")
    List<Object[]> mediaForPosts(Collection<Long> postIds);
}
