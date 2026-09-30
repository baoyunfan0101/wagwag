package com.wagwag.api.pet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "pets")
public class Pet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 80)
    private String species;

    @Column(length = 80)
    private String breed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PetGender gender;

    private LocalDate birthday;

    @Column(length = 500)
    private String bio;

    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Pet() {}

    public Pet(User owner) { this.owner = owner; }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return owner.getId(); }
    public String getName() { return name; }
    public String getSpecies() { return species; }
    public String getBreed() { return breed; }
    public PetGender getGender() { return gender; }
    public LocalDate getBirthday() { return birthday; }
    public String getBio() { return bio; }
    public String getAvatarUrl() { return avatarUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(PetInput input) {
        name = input.name().trim();
        species = input.species().trim();
        breed = trimNullable(input.breed());
        gender = input.gender();
        birthday = input.birthday();
        bio = trimNullable(input.bio());
    }

    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    private static String trimNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
