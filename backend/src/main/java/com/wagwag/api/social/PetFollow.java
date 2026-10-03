package com.wagwag.api.social;

import com.wagwag.api.pet.Pet;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pet_follows")
public class PetFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_pet_id", nullable = false)
    private Pet follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "following_pet_id", nullable = false)
    private Pet following;

    @Column(nullable = false)
    private boolean accepted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PetFollow() {}

    public boolean isAccepted() { return accepted; }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
