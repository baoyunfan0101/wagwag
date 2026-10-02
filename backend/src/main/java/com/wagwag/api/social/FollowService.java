package com.wagwag.api.social;

import com.wagwag.api.pet.Pet;
import com.wagwag.api.pet.PetRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FollowService {
    private final PetFollowRepository follows;
    private final PetRepository pets;
    private final long devPetId;

    public FollowService(PetFollowRepository follows, PetRepository pets,
                         @Value("${app.dev-pet-id:0}") long devPetId) {
        this.follows = follows;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public FollowStatus follow(long petId) {
        Pet actor = actor();
        Pet target = find(petId);
        if (actor.getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pets cannot follow themselves");
        }
        follows.insertIfAbsent(actor.getId(), petId);
        return statusFor(target, actor.getId());
    }

    @Transactional
    public FollowStatus unfollow(long petId) {
        Pet actor = actor();
        Pet target = find(petId);
        follows.deletePair(actor.getId(), petId);
        return statusFor(target, actor.getId());
    }

    @Transactional(readOnly = true)
    public FollowStatus status(long petId) {
        Pet actor = actor();
        return statusFor(find(petId), actor.getId());
    }

    @Transactional(readOnly = true)
    public PetListPage discover(int limit, int page) {
        long actorId = actor().getId();
        return page(pets.discover(actorId, pagination(limit, page)), actorId);
    }

    @Transactional(readOnly = true)
    public PetListPage followers(long petId, int limit, int page) {
        find(petId);
        long actorId = actor().getId();
        return page(follows.followers(petId, pagination(limit, page)), actorId);
    }

    @Transactional(readOnly = true)
    public PetListPage following(long petId, int limit, int page) {
        find(petId);
        long actorId = actor().getId();
        return page(follows.following(petId, pagination(limit, page)), actorId);
    }

    private PetListPage page(Page<Pet> result, long actorId) {
        List<Pet> items = result.getContent();
        Set<Long> followed = items.isEmpty() ? Set.of() : new HashSet<>(follows.followedIds(
            actorId, items.stream().map(Pet::getId).toList()));
        List<PetSummary> summaries = items.stream().map(pet ->
            new PetSummary(pet.getId(), pet.getName(), pet.getSpecies(), pet.getAvatarUrl(),
                followed.contains(pet.getId()))).toList();
        return new PetListPage(summaries, result.hasNext() ? result.getNumber() + 1 : null);
    }

    private FollowStatus statusFor(Pet target, long actorId) {
        long id = target.getId();
        return new FollowStatus(id, follows.countByFollowing_Id(id), follows.countByFollower_Id(id),
            follows.existsByFollower_IdAndFollowing_Id(actorId, id));
    }

    private Pet actor() {
        if (devPetId < 1) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is not configured");
        }
        return pets.findById(devPetId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing"));
    }

    private Pet find(long id) {
        return pets.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Pet not found"));
    }

    private static PageRequest pagination(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid list page or limit");
        }
        return PageRequest.of(page, limit);
    }
}
