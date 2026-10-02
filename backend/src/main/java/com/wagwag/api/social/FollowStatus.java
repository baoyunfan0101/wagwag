package com.wagwag.api.social;

public record FollowStatus(Long petId, long followerCount, long followingCount,
                           boolean followedByMe) {}
