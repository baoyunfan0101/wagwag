package com.wagwag.api.post;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    long countByPost_Id(Long postId);
    boolean existsByPost_IdAndPet_Id(Long postId, Long petId);
    long deleteByPost_IdAndPet_Id(Long postId, Long petId);

    @Modifying
    @Query(value = "INSERT INTO likes (post_id, pet_id) VALUES (:postId, :petId) "
        + "ON CONFLICT (post_id, pet_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(long postId, long petId);

    @Query("select l.post.id, count(l) from PostLike l where l.post.id in :postIds group by l.post.id")
    List<Object[]> countByPostIds(Collection<Long> postIds);

    @Query("select l.post.id from PostLike l where l.post.id in :postIds and l.pet.id = :petId")
    List<Long> likedPostIds(Collection<Long> postIds, long petId);
}
