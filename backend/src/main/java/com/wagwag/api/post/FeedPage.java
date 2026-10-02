package com.wagwag.api.post;

import java.util.List;

public record FeedPage(List<PostResponse> items, String nextCursor) {}
