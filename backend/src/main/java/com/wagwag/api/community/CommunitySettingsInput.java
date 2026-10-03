package com.wagwag.api.community;

import jakarta.validation.constraints.Size;

public record CommunitySettingsInput(@Size(max = 500) String description,
                                     @Size(max = 2000) String rules) {}
