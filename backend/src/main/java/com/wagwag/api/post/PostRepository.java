package com.wagwag.api.post;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface PostRepository extends JpaRepository<Post, Long> {
    @Query("select p from Post p join fetch p.pet where p.pet.id not in :hidden "
        + "and (p.pet.id = :actorId or p.pet.privateProfile = false or exists "
        + "(select f.id from PetFollow f where f.follower.id = :actorId "
        + "and f.following.id = p.pet.id and f.accepted = true)) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findFeed(long actorId, Collection<Long> hidden, Pageable page);

    @Query("select p from Post p join fetch p.pet where p.pet.id not in :hidden "
        + "and (p.pet.id = :actorId or p.pet.privateProfile = false or exists "
        + "(select f.id from PetFollow f where f.follower.id = :actorId "
        + "and f.following.id = p.pet.id and f.accepted = true)) "
        + "and (p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findFeedAfter(long actorId, Collection<Long> hidden,
                             Instant createdAt, long id, Pageable page);

    @Query("select p from Post p join fetch p.pet where p.pet.id not in :hidden and exists "
        + "(select f.id from PetFollow f where f.follower.id = :petId "
        + "and f.following.id = p.pet.id and f.accepted = true) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findFollowingFeed(long petId, Collection<Long> hidden, Pageable page);

    @Query("select p from Post p join fetch p.pet where p.pet.id not in :hidden and exists "
        + "(select f.id from PetFollow f where f.follower.id = :petId "
        + "and f.following.id = p.pet.id and f.accepted = true) "
        + "and (p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findFollowingFeedAfter(long petId, Collection<Long> hidden,
                                      Instant createdAt, long id, Pageable page);

    @Query("select p from Post p join fetch p.pet where exists "
        + "(select pc.postId from PostCommunity pc where pc.postId = p.id and pc.communityId = :communityId) "
        + "and p.pet.id not in :hidden "
        + "and (p.pet.id = :actorId or p.pet.privateProfile = false or exists "
        + "(select f.id from PetFollow f where f.follower.id = :actorId "
        + "and f.following.id = p.pet.id and f.accepted = true)) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findCommunityFeed(long communityId, long actorId, Collection<Long> hidden, Pageable page);

    @Query("select p from Post p join fetch p.pet where exists "
        + "(select pc.postId from PostCommunity pc where pc.postId = p.id and pc.communityId = :communityId) "
        + "and p.pet.id not in :hidden "
        + "and (p.pet.id = :actorId or p.pet.privateProfile = false or exists "
        + "(select f.id from PetFollow f where f.follower.id = :actorId "
        + "and f.following.id = p.pet.id and f.accepted = true)) "
        + "and (p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)) "
        + "order by p.createdAt desc, p.id desc")
    List<Post> findCommunityFeedAfter(long communityId, long actorId, Collection<Long> hidden,
                                      Instant createdAt, long id, Pageable page);
}
