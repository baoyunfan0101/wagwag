package com.wagwag.api.pet;

import com.wagwag.api.storage.AvatarStorage;
import com.wagwag.api.storage.AvatarStorage.UploadTicket;
import com.wagwag.api.social.FollowService;
import com.wagwag.api.social.SocialPairLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PetService {
    private final PetRepository pets;
    private final UserRepository users;
    private final AvatarStorage storage;
    private final FollowService follows;
    private final SocialPairLock pairLock;
    private final long devUserId;
    private final long devPetId;

    public PetService(PetRepository pets, UserRepository users, AvatarStorage storage,
                      FollowService follows, SocialPairLock pairLock,
                      @Value("${app.dev-user-id:0}") long devUserId,
                      @Value("${app.dev-pet-id:0}") long devPetId) {
        this.pets = pets;
        this.users = users;
        this.storage = storage;
        this.follows = follows;
        this.pairLock = pairLock;
        this.devUserId = devUserId;
        this.devPetId = devPetId;
    }

    @Transactional(readOnly = true)
    public PetResponse get(long id) { return PetResponse.from(find(id)); }

    @Transactional
    public PetResponse create(PetInput input) {
        User owner = users.findById(devUserId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development user is missing"));
        Pet pet = new Pet(owner);
        pet.update(input);
        return PetResponse.from(pets.saveAndFlush(pet));
    }

    @Transactional
    public PetResponse update(long id, PetInput input) {
        Pet pet = owned(id);
        pet.update(input);
        pets.flush();
        return PetResponse.from(pet);
    }

    @Transactional
    public PetResponse privacy(long id, boolean privateProfile) {
        if (id != devPetId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pet is not the active development pet");
        }
        pairLock.lockPet(id);
        Pet pet = owned(id);
        pet.setPrivateProfile(privateProfile);
        pets.flush();
        if (!privateProfile) follows.acceptPendingForPublic(id);
        return PetResponse.from(pet);
    }

    @Transactional(readOnly = true)
    public UploadTicket prepareAvatar(long id, String contentType) {
        owned(id);
        return storage.prepare(id, contentType);
    }

    @Transactional
    public PetResponse saveAvatar(long id, String key) {
        Pet pet = owned(id);
        pet.setAvatarUrl(storage.verifyAndGetUrl(id, key));
        pets.flush();
        return PetResponse.from(pet);
    }

    private Pet find(long id) {
        return pets.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Pet not found"));
    }

    private Pet owned(long id) {
        Pet pet = find(id);
        if (pet.getOwnerId() != devUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pet belongs to another user");
        }
        return pet;
    }
}
