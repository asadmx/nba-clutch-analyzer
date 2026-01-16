package com.asad.clutch_analyzer.service;

import com.asad.clutch_analyzer.model.GameEvent;
import com.asad.clutch_analyzer.model.PlayerStats;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ClutchService {

    // Backwards compatible (no close-game filter)
    public List<PlayerStats> buildLeaderboard(List<GameEvent> events, int clutchSeconds) {
        return buildLeaderboard(events, clutchSeconds, null);
    }

    /**
     * NEW: Optional close-game filter.
     * If closePoints is null -> behaves like before.
     * If closePoints is set -> only count plays where score margin <= closePoints.
     */
    public List<PlayerStats> buildLeaderboard(List<GameEvent> events, int clutchSeconds, Integer closePoints) {
        Map<String, PlayerStats> map = new HashMap<>();

        for (GameEvent e : events) {
            if (!e.isClutch(clutchSeconds, closePoints)) continue;

            // Key by player + team so names don’t collide
            String key = (e.team == null || e.team.isBlank())
                    ? e.player
                    : (e.player + "|" + e.team);

            map.putIfAbsent(key, new PlayerStats(e.player, e.team));
            map.get(key).applyEvent(e);
        }

        List<PlayerStats> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Integer.compare(b.clutchRating(), a.clutchRating()));
        return list;
    }
}
