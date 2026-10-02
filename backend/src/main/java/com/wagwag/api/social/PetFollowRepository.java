package com.wagwag.api.social;

import com.wagwag.api.pet.Pet;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PetFollowRepository extends JpaRepository<PetFollow, Long> {
    @Modifying
    @Query(value = "INSERT INTO pet_follows (follower_pet_id, following_pet_id) "
        + "VALUES (:followerId, :followingId) ON CONFLICT (follower_pet_id, following_pet_id) DO NOTHING",
        nativeQuery = true)
    int insertIfAbsent(long followerId, long followingId);

    @Modifying
    @Query(value = "DELETE FROM pet_follows WHERE follower_pet_id = :followerId "
        + "AND following_pet_id = :followingId", nativeQuery = true)
    int deletePair(long followerId, long followingId);

    long countByFollower_Id(long petId);
    long countByFollowing_Id(long petId);
    boolean existsByFollower_IdAndFollowing_Id(long followerId, long followingId);

    @Query(value = "select f.follower from PetFollow f where f.following.id = :petId "
        + "order by f.createdAt desc, f.id desc",
        countQuery = "select count(f) from PetFollow f where f.following.id = :petId")
    Page<Pet> followers(long petId, Pageable page);

    @Query(value = "select f.following from PetFollow f where f.follower.id = :petId "
        + "order by f.createdAt desc, f.id desc",
        countQuery = "select count(f) from PetFollow f where f.follower.id = :petId")
    Page<Pet> following(long petId, Pageable page);

    @Query("select f.following.id from PetFollow f where f.follower.id = :followerId "
        + "and f.following.id in :petIds")
    List<Long> followedIds(long followerId, Collection<Long> petIds);
}
