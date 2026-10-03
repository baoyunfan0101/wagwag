package com.wagwag.api.walk;

import com.wagwag.api.walk.TerritoryService.CreateResult;
import com.wagwag.api.walk.TerritoryService.TerritoryResponse;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/walks/{walkId}/territory")
public class TerritoryController {
    private final TerritoryService territories;

    public TerritoryController(TerritoryService territories) { this.territories = territories; }

    @PostMapping
    public ResponseEntity<TerritoryResponse> claim(@PathVariable long walkId) {
        CreateResult result = territories.claim(walkId);
        return result.created()
            ? ResponseEntity.created(URI.create("/api/walks/" + walkId + "/territory")).body(result.territory())
            : ResponseEntity.ok(result.territory());
    }

    @GetMapping
    public TerritoryResponse detail(@PathVariable long walkId) { return territories.detail(walkId); }
}
