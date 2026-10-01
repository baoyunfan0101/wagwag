package com.wagwag.api.pet;

import java.time.Instant;
import java.time.LocalDate;

public record PetResponse(
    Long id, Long ownerId, String name, String avatarUrl, String species,
    String breed, PetGender gender, LocalDate birthday, String bio,
    Instant createdAt, Instant updatedAt
) {
    public static PetResponse from(Pet pet) {
        return new PetResponse(pet.getId(), pet.getOwnerId(), pet.getName(),
            pet.getAvatarUrl(), pet.getSpecies(), pet.getBreed(), pet.getGender(),
            pet.getBirthday(), pet.getBio(), pet.getCreatedAt(), pet.getUpdatedAt());
    }
}
