package com.wagwag.api.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "post_media")
public class PostMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected PostMedia() {}

    public PostMedia(Post post, String url, int sortOrder) {
        this.post = post;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    public String getUrl() { return url; }
}
