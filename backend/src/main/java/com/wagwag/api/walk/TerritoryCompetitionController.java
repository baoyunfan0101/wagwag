package com.wagwag.api.walk;

import com.wagwag.api.walk.TerritoryService.LeaderboardEntry;
import com.wagwag.api.walk.TerritoryService.TerritoryPage;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/territories")
public class TerritoryCompetitionController {
    private final TerritoryService territories;

    public TerritoryCompetitionController(TerritoryService territories) { this.territories = territories; }

    @GetMapping("/history")
    public TerritoryPage history(@RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(defaultValue = "0") int page) {
        return territories.history(limit, page);
    }

    @GetMapping("/leaderboard")
    public List<LeaderboardEntry> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return territories.leaderboard(limit);
    }
}
