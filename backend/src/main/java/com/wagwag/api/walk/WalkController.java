package com.wagwag.api.walk;

import com.wagwag.api.walk.WalkService.WalkPage;
import com.wagwag.api.walk.WalkService.CreateResult;
import com.wagwag.api.walk.WalkService.WalkResponse;
import com.wagwag.api.walk.WalkService.NearbyWalkPage;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/walks")
public class WalkController {
    private final WalkService walks;

    public WalkController(WalkService walks) { this.walks = walks; }

    @PostMapping
    public ResponseEntity<WalkResponse> create(@Valid @RequestBody WalkInput input) {
        CreateResult result = walks.create(input);
        return result.created()
            ? ResponseEntity.created(URI.create("/api/walks/" + result.walk().id())).body(result.walk())
            : ResponseEntity.ok(result.walk());
    }

    @GetMapping
    public WalkPage list(@RequestParam(defaultValue = "20") int limit,
                         @RequestParam(defaultValue = "0") int page) {
        return walks.list(limit, page);
    }

    @GetMapping("/{id}")
    public WalkResponse detail(@PathVariable long id) { return walks.detail(id); }

    @GetMapping("/nearby")
    public NearbyWalkPage nearby(@RequestParam double latitude, @RequestParam double longitude,
                                @RequestParam(defaultValue = "1000") double radiusMeters,
                                @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "0") int page) {
        return walks.nearby(latitude, longitude, radiusMeters, limit, page);
    }
}
