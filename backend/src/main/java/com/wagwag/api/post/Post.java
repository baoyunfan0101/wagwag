package com.wagwag.api.post;

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
@Table(name = "posts")
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    @Column(length = 2000)
    private String body;

    @Column(name = "video_url", length = 2048)
    private String videoUrl;

    @Column(name = "video_thumbnail_url", length = 2048)
    private String videoThumbnailUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Post() {}

    public Post(Pet pet, String body) {
        this(pet, body, null, null);
    }

    public Post(Pet pet, String body, String videoUrl, String videoThumbnailUrl) {
        this.pet = pet;
        this.body = body;
        this.videoUrl = videoUrl;
        this.videoThumbnailUrl = videoThumbnailUrl;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Pet getPet() { return pet; }
    public String getBody() { return body; }
    public String getVideoUrl() { return videoUrl; }
    public String getVideoThumbnailUrl() { return videoThumbnailUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
