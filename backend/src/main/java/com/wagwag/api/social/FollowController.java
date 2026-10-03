package com.wagwag.api.social;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pets")
public class FollowController {
    private final FollowService service;

    public FollowController(FollowService service) { this.service = service; }

    @GetMapping("/discover")
    public PetListPage discover(@RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int page) {
        return service.discover(limit, page);
    }

    @GetMapping("/{id}/social")
    public FollowStatus status(@PathVariable long id) { return service.status(id); }

    @PostMapping("/{id}/follow")
    public FollowStatus follow(@PathVariable long id) { return service.follow(id); }

    @DeleteMapping("/{id}/follow")
    public FollowStatus unfollow(@PathVariable long id) { return service.unfollow(id); }

    @PostMapping("/{id}/block")
    public FollowStatus block(@PathVariable long id) { return service.block(id); }

    @DeleteMapping("/{id}/block")
    public FollowStatus unblock(@PathVariable long id) { return service.unblock(id); }

    @PostMapping("/{id}/mute")
    public FollowStatus mute(@PathVariable long id) { return service.mute(id); }

    @DeleteMapping("/{id}/mute")
    public FollowStatus unmute(@PathVariable long id) { return service.unmute(id); }

    @GetMapping("/{id}/followers")
    public PetListPage followers(@PathVariable long id,
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(defaultValue = "0") int page) {
        return service.followers(id, limit, page);
    }

    @GetMapping("/{id}/following")
    public PetListPage following(@PathVariable long id,
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(defaultValue = "0") int page) {
        return service.following(id, limit, page);
    }

    @GetMapping("/{id}/follow-requests")
    public PetListPage requests(@PathVariable long id,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int page) {
        return service.requests(id, limit, page);
    }

    @PostMapping("/{id}/follow-requests/{followerId}")
    public FollowStatus approve(@PathVariable long id, @PathVariable long followerId) {
        return service.approve(id, followerId);
    }

    @DeleteMapping("/{id}/follow-requests/{followerId}")
    public FollowStatus decline(@PathVariable long id, @PathVariable long followerId) {
        return service.decline(id, followerId);
    }
}
