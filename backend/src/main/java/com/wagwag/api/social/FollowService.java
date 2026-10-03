package com.wagwag.api.social;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.FollowCache.Relation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FollowService {
    private final PetFollowRepository follows;
    private final PetRepository pets;
    private final SocialRestrictions restrictions;
    private final FollowCache cache;
    private final SocialPairLock pairLock;
    private final long devPetId;

    public FollowService(PetFollowRepository follows, PetRepository pets,
                         SocialRestrictions restrictions, FollowCache cache, SocialPairLock pairLock,
                         @Value("${app.dev-pet-id:0}") long devPetId) {
        this.follows = follows;
        this.pets = pets;
        this.restrictions = restrictions;
        this.cache = cache;
        this.pairLock = pairLock;
        this.devPetId = devPetId;
    }

    @Transactional
    public FollowStatus follow(long petId) {
        lockActorPair(petId);
        Pet actor = actor();
        Pet target = find(petId);
        if (restrictions.blockedEitherWay(actor.getId(), petId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Follow is blocked");
        }
        if (target.isPrivateProfile()) {
            follows.insertIfAbsent(actor.getId(), petId, false);
        } else {
            follows.acceptPending(actor.getId(), petId);
            follows.insertIfAbsent(actor.getId(), petId, true);
        }
        cache.evictAfterCommit(actor.getId(), petId);
        return statusFor(target, actor.getId(), false);
    }

    @Transactional
    public FollowStatus unfollow(long petId) {
        lockActorPair(petId);
        Pet actor = actor();
        Pet target = find(petId);
        follows.deletePair(actor.getId(), petId);
        cache.evictAfterCommit(actor.getId(), petId);
        return statusFor(target, actor.getId(), false);
    }

    @Transactional(readOnly = true)
    public FollowStatus status(long petId) {
        long actorId = actor().getId();
        return statusFor(find(petId), actorId, true);
    }

    @Transactional
    public FollowStatus block(long petId) {
        lockActorPair(petId);
        long actorId = actor().getId();
        Pet target = find(petId);
        restrictions.block(actorId, petId);
        follows.deletePair(actorId, petId);
        follows.deletePair(petId, actorId);
        restrictions.unmute(actorId, petId);
        cache.evictAfterCommit(actorId, petId);
        cache.evictAfterCommit(petId, actorId);
        return statusFor(target, actorId, false);
    }

    @Transactional
    public FollowStatus unblock(long petId) {
        lockActorPair(petId);
        long actorId = actor().getId();
        Pet target = find(petId);
        restrictions.unblock(actorId, petId);
        return statusFor(target, actorId, false);
    }

    @Transactional
    public FollowStatus mute(long petId) {
        long actorId = actor().getId();
        Pet target = find(petId);
        differentPets(actorId, petId);
        restrictions.mute(actorId, petId);
        return statusFor(target, actorId, false);
    }

    @Transactional
    public FollowStatus unmute(long petId) {
        long actorId = actor().getId();
        Pet target = find(petId);
        restrictions.unmute(actorId, petId);
        return statusFor(target, actorId, false);
    }

    @Transactional(readOnly = true)
    public PetListPage discover(int limit, int page) {
        long actorId = actor().getId();
        return page(pets.discover(actorId, visibleIds(actorId), pagination(limit, page)), actorId);
    }

    @Transactional(readOnly = true)
    public PetListPage followers(long petId, int limit, int page) {
        long actorId = actor().getId();
        visibleList(find(petId), actorId);
        return page(follows.followers(petId, visibleIds(actorId), pagination(limit, page)), actorId);
    }

    @Transactional(readOnly = true)
    public PetListPage following(long petId, int limit, int page) {
        long actorId = actor().getId();
        visibleList(find(petId), actorId);
        return page(follows.following(petId, visibleIds(actorId), pagination(limit, page)), actorId);
    }

    @Transactional(readOnly = true)
    public PetListPage requests(long petId, int limit, int page) {
        ownPet(petId);
        return page(follows.requests(petId, pagination(limit, page)), petId);
    }

    @Transactional
    public FollowStatus approve(long petId, long followerId) {
        pairLock.lock(petId, followerId);
        Pet actor = ownPet(petId);
        find(followerId);
        if (restrictions.blockedEitherWay(followerId, petId)
                || follows.acceptPending(followerId, petId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Follow request not found");
        }
        cache.evictAfterCommit(followerId, petId);
        return statusFor(actor, petId, false);
    }

    @Transactional
    public FollowStatus decline(long petId, long followerId) {
        pairLock.lock(petId, followerId);
        Pet actor = ownPet(petId);
        follows.declinePending(followerId, petId);
        cache.evictAfterCommit(followerId, petId);
        return statusFor(actor, petId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acceptPendingForPublic(long petId) {
        List<Long> followerIds = follows.pendingFollowerIds(petId);
        if (followerIds.isEmpty()) return;
        follows.acceptAllPending(petId);
        for (long followerId : followerIds) cache.evictAfterCommit(followerId, petId);
    }

    private PetListPage page(Page<Pet> result, long actorId) {
        List<Pet> items = result.getContent();
        List<Long> ids = items.stream().map(Pet::getId).toList();
        Set<Long> followed = ids.isEmpty() ? Set.of() : new HashSet<>(follows.followedIds(actorId, ids));
        Set<Long> requested = ids.isEmpty() ? Set.of() : new HashSet<>(follows.requestedIds(actorId, ids));
        List<PetSummary> summaries = items.stream().map(pet ->
            new PetSummary(pet.getId(), pet.getName(), pet.getSpecies(), pet.getAvatarUrl(),
                pet.isPrivateProfile(), followed.contains(pet.getId()), requested.contains(pet.getId()))).toList();
        return new PetListPage(summaries, result.hasNext() ? result.getNumber() + 1 : null);
    }

    private FollowStatus statusFor(Pet target, long actorId, boolean cached) {
        long id = target.getId();
        long followers = cached ? cache.followerCount(id, () -> follows.countByFollowing_IdAndAcceptedTrue(id))
            : follows.countByFollowing_IdAndAcceptedTrue(id);
        long following = cached ? cache.followingCount(id, () -> follows.countByFollower_IdAndAcceptedTrue(id))
            : follows.countByFollower_IdAndAcceptedTrue(id);
        Relation relation = cached ? cache.relation(actorId, id, () -> relationFromDb(actorId, id))
            : relationFromDb(actorId, id);
        return new FollowStatus(id, followers, following, relation == Relation.FOLLOWING,
            relation == Relation.REQUESTED, target.isPrivateProfile(),
            restrictions.blockedBy(actorId, id), restrictions.mutedBy(actorId, id));
    }

    private Relation relationFromDb(long actorId, long targetId) {
        return follows.findByFollower_IdAndFollowing_Id(actorId, targetId)
            .map(follow -> follow.isAccepted() ? Relation.FOLLOWING : Relation.REQUESTED)
            .orElse(Relation.NONE);
    }

    private List<Long> visibleIds(long actorId) {
        List<Long> blocked = restrictions.blockedPetIds(actorId);
        return blocked.isEmpty() ? List.of(0L) : blocked;
    }

    private void visibleList(Pet target, long actorId) {
        long targetId = target.getId();
        if (actorId != targetId && (restrictions.blockedEitherWay(actorId, targetId)
                || (target.isPrivateProfile()
                    && !follows.existsByFollower_IdAndFollowing_IdAndAcceptedTrue(actorId, targetId)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pet list not found");
        }
    }

    private Pet actor() {
        if (devPetId < 1) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is not configured");
        }
        return pets.findById(devPetId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing"));
    }

    private Pet ownPet(long petId) {
        Pet pet = actor();
        if (pet.getId() != petId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pet is not the active development pet");
        }
        return pet;
    }

    private Pet find(long id) {
        return pets.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Pet not found"));
    }

    private static void differentPets(long actorId, long targetId) {
        if (actorId == targetId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pets cannot target themselves");
        }
    }

    private void lockActorPair(long targetId) {
        differentPets(devPetId, targetId);
        pairLock.lock(devPetId, targetId);
    }

    private static PageRequest pagination(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid list page or limit");
        }
        return PageRequest.of(page, limit);
    }
}
