package com.asad.clutch_analyzer.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class SportsApiService {

    private final RestClient client = RestClient.create();
    private static final String BASE = "https://www.thesportsdb.com/api/v1/json/3";

    @SuppressWarnings("unchecked")
    public Map<String, Object> searchTeamSmart(String teamName) {
        // First try exact name
        Map<String, Object> response = fetch(teamName);
        Map<String, Object> team = firstTeamOrNull(response);
        if (team != null) return team;

        // Fallback: try removing city (e.g. "Boston Celtics" → "Celtics")
        if (teamName.contains(" ")) {
            String fallback = teamName.substring(teamName.indexOf(" ") + 1);
            response = fetch(fallback);
            team = firstTeamOrNull(response);
            if (team != null) return team;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String q) {
        String url = BASE + "/searchteams.php?t=" + q.replace(" ", "%20");
        return client.get().uri(url).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstTeamOrNull(Map<String, Object> apiResponse) {
        if (apiResponse == null) return null;
        Object teamsObj = apiResponse.get("teams");
        if (!(teamsObj instanceof List)) return null;
        List<Object> teams = (List<Object>) teamsObj;
        if (teams.isEmpty()) return null;
        return (Map<String, Object>) teams.get(0);
    }
}
