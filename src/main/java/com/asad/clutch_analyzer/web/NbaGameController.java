package com.asad.clutch_analyzer.web;

import com.asad.clutch_analyzer.service.ClutchService;
import com.asad.clutch_analyzer.service.ClutchestGamesService;
import com.asad.clutch_analyzer.service.ClutchestPlayersService;
import com.asad.clutch_analyzer.service.NbaLiveDataService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Controller
public class NbaGameController {

    private final NbaLiveDataService nbaLiveDataService;
    private final ClutchService clutchService;
    private final ClutchestGamesService clutchestGamesService;
    private final ClutchestPlayersService clutchestPlayersService;

    public NbaGameController(NbaLiveDataService nbaLiveDataService,
                             ClutchService clutchService,
                             ClutchestGamesService clutchestGamesService,
                             ClutchestPlayersService clutchestPlayersService) {
        this.nbaLiveDataService = nbaLiveDataService;
        this.clutchService = clutchService;
        this.clutchestGamesService = clutchestGamesService;
        this.clutchestPlayersService = clutchestPlayersService;
    }

    @GetMapping("/nba/game")
    public String analyzeRealGame(@RequestParam String gameId,
                                  @RequestParam(defaultValue = "300") int clutchSeconds,
                                  @RequestParam(required = false) Integer closePoints,
                                  Model model) {

        // sanitize closePoints
        if (closePoints != null) {
            if (closePoints <= 0) closePoints = null; // treat <=0 as "Any"
            else if (closePoints > 25) closePoints = 25; // keep it reasonable
        }

        var header = nbaLiveDataService.fetchGameHeader(gameId);
        var events = nbaLiveDataService.fetchAndConvertPlayByPlay(gameId);

        // Always compute both windows (NOW with closePoints filter)
        var leaderboard5 = clutchService.buildLeaderboard(events, 300, closePoints);
        var leaderboard2 = clutchService.buildLeaderboard(events, 120, closePoints);

        // Custom (also filtered)
        var leaderboardCustom = clutchService.buildLeaderboard(events, clutchSeconds, closePoints);

        model.addAttribute("isNbaMode", true);
        model.addAttribute("homeTeam", header.homeTeam);
        model.addAttribute("awayTeam", header.awayTeam);
        model.addAttribute("gameDate", header.gameDate);

        // Final score + FT/OT label
        model.addAttribute("homeScore", header.homeScore);
        model.addAttribute("awayScore", header.awayScore);

        String timeLabel = null;
        if (header.periods != null) {
            if (header.periods <= 4) timeLabel = "FT";
            else {
                int ot = header.periods - 4;
                timeLabel = (ot == 1) ? "OT" : (ot + "OT");
            }
        } else if (header.homeScore != null && header.awayScore != null) {
            timeLabel = "FT";
        }
        model.addAttribute("timeLabel", timeLabel);

        if (header.homeTeamId != null) {
            model.addAttribute("homeLogoUrl",
                    "https://cdn.nba.com/logos/nba/" + header.homeTeamId + "/global/L/logo.svg");
        } else {
            model.addAttribute("homeLogoUrl", null);
        }

        if (header.awayTeamId != null) {
            model.addAttribute("awayLogoUrl",
                    "https://cdn.nba.com/logos/nba/" + header.awayTeamId + "/global/L/logo.svg");
        } else {
            model.addAttribute("awayLogoUrl", null);
        }

        model.addAttribute("leaderboard5", leaderboard5);
        model.addAttribute("leaderboard2", leaderboard2);
        model.addAttribute("leaderboard", leaderboardCustom);
        model.addAttribute("clutchSeconds", clutchSeconds);

        model.addAttribute("closePoints", closePoints);

        model.addAttribute("teamInfo", null);
        model.addAttribute("teamQuery", "NBA Game " + gameId);

        return "results";
    }

    // -----------------------------
    // Clutchest Games League View
    // -----------------------------
    @GetMapping("/nba/clutchest")
    public String clutchestGames(@RequestParam(defaultValue = "14") int days,
                                 @RequestParam(defaultValue = "25") int top,
                                 Model model) {

        // guard rails
        days = Math.max(0, days); // allow 0 = all season
        top = Math.max(5, Math.min(top, 100));

        model.addAttribute("days", days);
        model.addAttribute("top", top);
        model.addAttribute("rows", clutchestGamesService.topClutchestGames(days, top));

        return "clutchest";
    }

    // -----------------------------
    // Clutchest Player Games League View
    // -----------------------------
    @GetMapping("/nba/clutchplayers")
    public String clutchestPlayers(@RequestParam(defaultValue = "0") int days,
                                   @RequestParam(defaultValue = "50") int top,
                                   @RequestParam(defaultValue = "rating") String rankBy,
                                   Model model) {

        days = Math.max(0, days); // allow 0 = all season
        top = Math.max(10, Math.min(top, 200));

        String rank = (rankBy == null) ? "rating" : rankBy.trim().toLowerCase(Locale.ROOT);
        if (!rank.equals("rating") && !rank.equals("points")) rank = "rating";

        model.addAttribute("days", days);
        model.addAttribute("top", top);
        model.addAttribute("rankBy", rank);
        model.addAttribute("rows", clutchestPlayersService.topClutchPlayerGames(days, top, rank));

        return "clutchplayers";
    }

    /**
     * Recent games JSON for homepage UX (from local CSV index).
     */
    @GetMapping(value = "/nba/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<RecentGame> recentGames(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer days
    ) {
        int safeLimit = (limit == null) ? 50 : Math.max(1, Math.min(limit, 200));
        int safeDays = (days == null) ? 0 : Math.max(1, Math.min(days, 30)); // 0 = ignore

        DateTimeFormatter MMM_D_YYYY = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US);
        DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

        class Row {
            RecentGame game;
            LocalDate parsedDate;
            Row(RecentGame game, LocalDate parsedDate) {
                this.game = game;
                this.parsedDate = parsedDate;
            }
        }

        List<Row> rows = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("static/nba_game_index.csv").getInputStream(),
                StandardCharsets.UTF_8))) {

            String header = br.readLine(); // skip header
            if (header == null) return List.of();

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 4) continue;

                String gameId = parts[0].trim();
                String dateRaw = parts[1].trim();
                String away = parts[2].trim();
                String home = parts[3].trim();

                if (gameId.isEmpty() || dateRaw.isEmpty()) continue;

                LocalDate parsed;
                try {
                    if (dateRaw.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        parsed = LocalDate.parse(dateRaw, ISO);
                    } else {
                        parsed = LocalDate.parse(dateRaw, MMM_D_YYYY);
                    }
                } catch (DateTimeParseException e) {
                    continue;
                }

                RecentGame g = new RecentGame(
                        gameId,
                        dateRaw,
                        away.isEmpty() ? "TBD" : away,
                        home.isEmpty() ? "TBD" : home
                );

                rows.add(new Row(g, parsed));
            }

        } catch (Exception e) {
            return List.of();
        }

        rows.sort((a, b) -> b.parsedDate.compareTo(a.parsedDate));

        if (safeDays > 0) {
            Set<LocalDate> keepDates = new LinkedHashSet<>();
            List<RecentGame> out = new ArrayList<>();

            for (Row r : rows) {
                keepDates.add(r.parsedDate);
                if (keepDates.size() >= safeDays) break;
            }
            for (Row r : rows) {
                if (keepDates.contains(r.parsedDate)) out.add(r.game);
            }
            return out;
        }

        List<RecentGame> out = new ArrayList<>();
        for (int i = 0; i < rows.size() && out.size() < safeLimit; i++) {
            out.add(rows.get(i).game);
        }
        return out;
    }

    public record RecentGame(String gameId, String date, String away, String home) {}
}
