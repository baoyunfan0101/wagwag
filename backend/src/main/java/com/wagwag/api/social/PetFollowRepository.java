package com.wagwag.api.social;

import com.wagwag.api.pet.Pet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PetFollowRepository extends JpaRepository<PetFollow, Long> {
    @Modifying
    @Query(value = "INSERT INTO pet_follows (follower_pet_id, following_pet_id, accepted) "
        + "VALUES (:followerId, :followingId, :accepted) "
        + "ON CONFLICT (follower_pet_id, following_pet_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(long followerId, long followingId, boolean accepted);

    @Modifying
    @Query(value = "UPDATE pet_follows SET accepted = TRUE WHERE follower_pet_id = :followerId "
        + "AND following_pet_id = :followingId AND accepted = FALSE", nativeQuery = true)
    int acceptPending(long followerId, long followingId);

    @Query("select f.follower.id from PetFollow f where f.following.id = :petId and f.accepted = false")
    List<Long> pendingFollowerIds(long petId);

    @Modifying
    @Query(value = "UPDATE pet_follows SET accepted = TRUE WHERE following_pet_id = :petId "
        + "AND accepted = FALSE", nativeQuery = true)
    int acceptAllPending(long petId);

    @Modifying
    @Query(value = "DELETE FROM pet_follows WHERE follower_pet_id = :followerId "
        + "AND following_pet_id = :followingId", nativeQuery = true)
    int deletePair(long followerId, long followingId);

    @Modifying
    @Query(value = "DELETE FROM pet_follows WHERE follower_pet_id = :followerId "
        + "AND following_pet_id = :followingId AND accepted = FALSE", nativeQuery = true)
    int declinePending(long followerId, long followingId);

    long countByFollower_IdAndAcceptedTrue(long petId);
    long countByFollowing_IdAndAcceptedTrue(long petId);
    boolean existsByFollower_IdAndFollowing_IdAndAcceptedTrue(long followerId, long followingId);
    Optional<PetFollow> findByFollower_IdAndFollowing_Id(long followerId, long followingId);

    @Query(value = "select f.follower from PetFollow f where f.following.id = :petId "
        + "and f.accepted = true and f.follower.id not in :hidden "
        + "order by f.createdAt desc, f.id desc",
        countQuery = "select count(f) from PetFollow f where f.following.id = :petId "
            + "and f.accepted = true and f.follower.id not in :hidden")
    Page<Pet> followers(long petId, Collection<Long> hidden, Pageable page);

    @Query(value = "select f.following from PetFollow f where f.follower.id = :petId "
        + "and f.accepted = true and f.following.id not in :hidden "
        + "order by f.createdAt desc, f.id desc",
        countQuery = "select count(f) from PetFollow f where f.follower.id = :petId "
            + "and f.accepted = true and f.following.id not in :hidden")
    Page<Pet> following(long petId, Collection<Long> hidden, Pageable page);

    @Query(value = "select f.follower from PetFollow f where f.following.id = :petId "
        + "and f.accepted = false order by f.createdAt desc, f.id desc",
        countQuery = "select count(f) from PetFollow f where f.following.id = :petId "
            + "and f.accepted = false")
    Page<Pet> requests(long petId, Pageable page);

    @Query("select f.following.id from PetFollow f where f.follower.id = :followerId "
        + "and f.accepted = true and f.following.id in :petIds")
    List<Long> followedIds(long followerId, Collection<Long> petIds);

    @Query("select f.following.id from PetFollow f where f.follower.id = :followerId "
        + "and f.accepted = false and f.following.id in :petIds")
    List<Long> requestedIds(long followerId, Collection<Long> petIds);
}
