package com.wagwag.api.community;

import com.wagwag.api.community.CommunityService.CommunityPage;
import com.wagwag.api.community.CommunityService.CommunityResponse;
import com.wagwag.api.community.CommunityService.MemberPage;
import com.wagwag.api.post.FeedPage;
import com.wagwag.api.post.PostService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/communities")
public class CommunityController {
    private final CommunityService communities;
    private final PostService posts;

    public CommunityController(CommunityService communities, PostService posts) {
        this.communities = communities;
        this.posts = posts;
    }

    @PostMapping
    public ResponseEntity<CommunityResponse> create(@Valid @RequestBody CommunityInput input) {
        CommunityResponse created = communities.create(input);
        return ResponseEntity.created(URI.create("/api/communities/" + created.id())).body(created);
    }

    @GetMapping
    public CommunityPage list(@RequestParam(required = false) String query,
                              @RequestParam(defaultValue = "20") int limit,
                              @RequestParam(defaultValue = "0") int page) {
        return communities.list(query, limit, page);
    }

    @GetMapping("/{id}")
    public CommunityResponse detail(@PathVariable long id) { return communities.detail(id); }

    @PostMapping("/{id}/members")
    public CommunityResponse join(@PathVariable long id) { return communities.join(id); }

    @DeleteMapping("/{id}/members")
    public CommunityResponse leave(@PathVariable long id) { return communities.leave(id); }

    @GetMapping("/{id}/members")
    public MemberPage members(@PathVariable long id, @RequestParam(defaultValue = "20") int limit,
                              @RequestParam(defaultValue = "0") int page) {
        return communities.members(id, limit, page);
    }

    @GetMapping("/{id}/feed")
    public FeedPage feed(@PathVariable long id, @RequestParam(defaultValue = "20") int limit,
                         @RequestParam(required = false) String cursor) {
        return posts.communityFeed(id, limit, cursor);
    }
}
