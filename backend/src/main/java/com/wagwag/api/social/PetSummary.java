package com.wagwag.api.social;

public record PetSummary(Long id, String name, String species, String avatarUrl,
                         boolean followedByMe) {}
