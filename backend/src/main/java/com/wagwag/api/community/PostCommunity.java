package com.wagwag.api.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "post_communities")
public class PostCommunity {
    @Id
    @Column(name = "post_id")
    private Long postId;

    @Column(name = "community_id", nullable = false)
    private Long communityId;

    protected PostCommunity() {}
}
