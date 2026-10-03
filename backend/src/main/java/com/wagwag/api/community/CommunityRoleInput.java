package com.wagwag.api.community;

import jakarta.validation.constraints.NotNull;

public record CommunityRoleInput(@NotNull Role role) {
    public enum Role { MEMBER, MODERATOR }
}
