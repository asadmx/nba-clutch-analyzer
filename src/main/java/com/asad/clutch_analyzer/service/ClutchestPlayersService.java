package com.asad.clutch_analyzer.service;

import com.asad.clutch_analyzer.model.ClutchPlayerRow;
import com.asad.clutch_analyzer.model.GameEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ClutchestPlayersService {

    private final NbaLiveDataService nbaLiveDataService;

    // Cache successful per-game computations for a long time
    private static final long GAME_OK_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    // Cache failures briefly
    private static final long GAME_FAIL_TTL_MS = 5L * 60 * 1000;     // 5 min
    // Request-level cache
    private static final long REQUEST_TTL_MS = 2L * 60 * 1000;       // 2 min

    private static class GameCacheEntry {
        final long createdAtMs;
        final boolean ok;
        final List<ClutchPlayerRow> rows;
        GameCacheEntry(long createdAtMs, boolean ok, List<ClutchPlayerRow> rows) {
            this.createdAtMs = createdAtMs;
            this.ok = ok;
            this.rows = rows;
        }
    }

    private static class RequestCacheEntry {
        final long createdAtMs;
        final List<ClutchPlayerRow> rows;
        RequestCacheEntry(long createdAtMs, List<ClutchPlayerRow> rows) {
            this.createdAtMs = createdAtMs;
            this.rows = rows;
        }
    }

    private final ConcurrentHashMap<String, GameCacheEntry> gameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RequestCacheEntry> requestCache = new ConcurrentHashMap<>();

    public ClutchestPlayersService(NbaLiveDataService nbaLiveDataService) {
        this.nbaLiveDataService = nbaLiveDataService;
    }

    // -------------------------------
    // Ranking toggle support
    // -------------------------------

    private static String normalizeRankBy(String rankBy) {
        if (rankBy == null) return "rating";
        String v = rankBy.trim().toLowerCase(Locale.ROOT);
        if (v.equals("points") || v.equals("clutchpoints") || v.equals("clutch_points")) return "points";
        return "rating";
    }

    private static int scoreOf(ClutchPlayerRow row, String rankByNorm) {
        if (row == null) return Integer.MIN_VALUE;
        if ("points".equals(rankByNorm)) return row.clutchPoints;
        return row.clutchRating();
    }

    private static void pushTop(PriorityQueue<ClutchPlayerRow> heap, ClutchPlayerRow row, int topN, String rankByNorm) {
        if (row == null) return;

        if (heap.size() < topN) {
            heap.add(row);
            return;
        }

        int rowScore = scoreOf(row, rankByNorm);
        int worstScore = scoreOf(heap.peek(), rankByNorm);

        if (rowScore > worstScore) {
            heap.poll();
            heap.add(row);
        }
    }

    private static String normalizeNameKey(String s) {
        if (s == null) return null;
        return s.trim()
                .toLowerCase(Locale.ROOT)
                .replace(".", "")
                .replace("’", "'")
                .replace("'", "")
                .replace("-", " ")
                .replaceAll("\\s+", " ");
    }

    private static String formatMmSs(Integer seconds) {
        if (seconds == null || seconds <= 0) return null;
        int mm = seconds / 60;
        int ss = seconds % 60;
        return String.format("%d:%02d", mm, ss);
    }

    private static List<ClutchPlayerRow> cloneRows(List<ClutchPlayerRow> rows) {
        List<ClutchPlayerRow> out = new ArrayList<>(rows.size());
        for (ClutchPlayerRow r : rows) {
            ClutchPlayerRow c = new ClutchPlayerRow(r.gameId, r.date, r.away, r.home);
            c.player = r.player;
            c.team = r.team;

            // NEW: clutch minutes
            c.clutchMinutes = r.clutchMinutes;

            // stats
            c.points = r.points;
            c.assists = r.assists;
            c.steals = r.steals;
            c.blocks = r.blocks;
            c.turnovers = r.turnovers;
            c.missedFg = r.missedFg;
            c.missedFt = r.missedFt;

            c.clutchPoints = r.clutchPoints;

            c.finalScore = r.finalScore;
            c.ot = r.ot;
            c.note = r.note;
            out.add(c);
        }
        return out;
    }

    /**
     * Backwards compatible (default rankBy=rating)
     */
    public List<ClutchPlayerRow> topClutchPlayerGames(int daysBack, int topN) {
        return topClutchPlayerGames(daysBack, topN, "rating");
    }

    /**
     * Top clutch player-games across:
     * - daysBack > 0: last {daysBack} unique game dates
     * - daysBack == 0: ALL season (no limit)
     *
     * Rules:
     * - clutch = last 5:00 of 4Q + all OT
     * - include ONLY games that were close (<=7) at least once during clutch
     *
     * Ranking:
     * - rating => clutchRating
     * - points => clutchPoints
     */
    public List<ClutchPlayerRow> topClutchPlayerGames(int daysBack, int topN, String rankBy) {

        String rankByNorm = normalizeRankBy(rankBy);

        long now = System.currentTimeMillis();
        String reqKey = "days=" + daysBack + "|top=" + topN + "|rankBy=" + rankByNorm;

        RequestCacheEntry reqHit = requestCache.get(reqKey);
        if (reqHit != null && (now - reqHit.createdAtMs) < REQUEST_TTL_MS) {
            return reqHit.rows;
        }

        List<BaseGame> games = loadPlayedGames(daysBack);
        if (games.isEmpty()) {
            requestCache.put(reqKey, new RequestCacheEntry(now, List.of()));
            return List.of();
        }

        int threads = Math.max(6, Math.min(12, Runtime.getRuntime().availableProcessors() * 2));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<List<ClutchPlayerRow>> cs = new ExecutorCompletionService<>(pool);

        try {
            int submitted = 0;

            // Keep only Top N globally (min-heap by selected score)
            PriorityQueue<ClutchPlayerRow> topHeap =
                    new PriorityQueue<>(Comparator.comparingInt(r -> scoreOf(r, rankByNorm)));

            for (BaseGame g : games) {
                String gid = normalizeGameId(g.gameId);
                g.gameId = gid;

                GameCacheEntry hit = gameCache.get(gid);
                if (hit != null) {
                    long age = now - hit.createdAtMs;
                    boolean okFresh = hit.ok && age < GAME_OK_TTL_MS;
                    boolean failFresh = !hit.ok && age < GAME_FAIL_TTL_MS;

                    if (okFresh) {
                        for (ClutchPlayerRow r : hit.rows) pushTop(topHeap, r, topN, rankByNorm);
                        continue;
                    }
                    if (failFresh) {
                        continue;
                    }
                }

                submitted++;
                cs.submit(() -> computePerGamePlayerRows(g));
            }

            for (int i = 0; i < submitted; i++) {
                Future<List<ClutchPlayerRow>> f = cs.take();
                List<ClutchPlayerRow> rows;
                try {
                    rows = f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    continue;
                }
                if (rows == null) continue;

                String gid = null;
                if (!rows.isEmpty() && rows.get(0) != null) gid = rows.get(0).gameId;

                boolean ok = true;
                if (rows.size() == 1 && rows.get(0).note != null && rows.get(0).note.equals("No PBP")) {
                    ok = false;
                }

                if (gid != null && !gid.isBlank()) {
                    gameCache.put(gid, new GameCacheEntry(System.currentTimeMillis(), ok, cloneRows(rows)));
                }

                for (ClutchPlayerRow r : rows) {
                    if (r.note == null) pushTop(topHeap, r, topN, rankByNorm);
                }
            }

            List<ClutchPlayerRow> out = new ArrayList<>(topHeap);
            out.sort((a, b) -> Integer.compare(scoreOf(b, rankByNorm), scoreOf(a, rankByNorm)));

            requestCache.put(reqKey, new RequestCacheEntry(System.currentTimeMillis(), out));
            return out;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------
    // Per-game compute
    // -------------------------------------------------------

    private static boolean isClutch(GameEvent e) {
        if (e == null) return false;
        if (e.quarter == 4) return e.clockSeconds <= 300;
        return e.quarter > 4; // OT entire period
    }

    private static class StatLine {
        int points, assists, steals, blocks, turnovers, missedFg, missedFt;
        String team;
    }

    private List<ClutchPlayerRow> computePerGamePlayerRows(BaseGame base) {

        String gid = normalizeGameId(base.gameId);

        List<GameEvent> events;
        try {
            events = nbaLiveDataService.fetchAndConvertPlayByPlay(gid);
        } catch (Exception ex) {
            ClutchPlayerRow fail = new ClutchPlayerRow(gid, base.dateRaw, base.away, base.home);
            fail.note = "No PBP";
            return List.of(fail);
        }

        if (events == null || events.isEmpty()) {
            ClutchPlayerRow fail = new ClutchPlayerRow(gid, base.dateRaw, base.away, base.home);
            fail.note = "No PBP";
            return List.of(fail);
        }

        // NEW: clutch seconds played (by normalized name key)
        Map<String, Integer> clutchSecondsByName = nbaLiveDataService.fetchClutchSecondsByPlayerName(gid);

        // Sort: period asc, clock desc
        events.sort((a, b) -> {
            int p = Integer.compare(a.quarter, b.quarter);
            if (p != 0) return p;
            return Integer.compare(b.clockSeconds, a.clockSeconds);
        });

        int awayScore = 0;
        int homeScore = 0;

        boolean ot = false;
        boolean wasEverCloseInClutch = false;

        Map<String, StatLine> byPlayer = new HashMap<>();

        for (GameEvent e : events) {
            if (e.quarter > 4) ot = true;

            // Running score based on team tricodes
            int pts = e.points;
            if (pts > 0) {
                if (base.home != null && base.home.equalsIgnoreCase(e.team)) homeScore += pts;
                else if (base.away != null && base.away.equalsIgnoreCase(e.team)) awayScore += pts;
            }

            if (!isClutch(e)) continue;

            int margin = homeScore - awayScore;
            if (Math.abs(margin) <= 7) wasEverCloseInClutch = true;

            if (e.player == null || e.player.isBlank()) continue;

            StatLine s = byPlayer.computeIfAbsent(e.player, k -> new StatLine());
            if (s.team == null && e.team != null && !e.team.isBlank()) s.team = e.team;

            String ev = (e.event == null ? "" : e.event);

            switch (ev) {
                case "SHOT_MADE_2":
                case "SHOT_MADE_3":
                case "FT_MADE":
                    s.points += pts; // 2/3/1
                    break;

                case "ASSIST":
                    s.assists += 1;
                    break;

                case "STEAL":
                    s.steals += 1;
                    break;

                case "BLOCK":
                    s.blocks += 1;
                    break;

                case "TURNOVER":
                    s.turnovers += 1;
                    break;

                case "SHOT_MISSED_2":
                case "SHOT_MISSED_3":
                    s.missedFg += 1;
                    break;

                case "FT_MISSED":
                    s.missedFt += 1;
                    break;

                default:
                    break;
            }
        }

        // Close games only
        if (!wasEverCloseInClutch) {
            return List.of();
        }

        String finalScore = base.away + " " + awayScore + " - " + homeScore + " " + base.home + (ot ? " (OT)" : "");

        List<ClutchPlayerRow> rows = new ArrayList<>();

        for (Map.Entry<String, StatLine> ent : byPlayer.entrySet()) {
            String player = ent.getKey();
            StatLine s = ent.getValue();

            // only include players that actually did something in clutch window
            int totalActions =
                    s.points + s.assists + s.steals + s.blocks + s.turnovers + s.missedFg + s.missedFt;
            if (totalActions == 0) continue;

            ClutchPlayerRow r = new ClutchPlayerRow(gid, base.dateRaw, base.away, base.home);
            r.player = player;
            r.team = (s.team == null ? "" : s.team);

            // NEW: clutch minutes played (time on court during clutch windows)
            if (clutchSecondsByName != null && !clutchSecondsByName.isEmpty()) {
                Integer sec = clutchSecondsByName.get(normalizeNameKey(player));
                r.clutchMinutes = formatMmSs(sec);
            }

            r.points = s.points;
            r.assists = s.assists;
            r.steals = s.steals;
            r.blocks = s.blocks;
            r.turnovers = s.turnovers;
            r.missedFg = s.missedFg;
            r.missedFt = s.missedFt;

            // mirror for display
            r.clutchPoints = s.points;

            r.finalScore = finalScore;
            r.ot = ot;

            rows.add(r);
        }

        // deterministic
        rows.sort((a, b) -> Integer.compare(b.clutchRating(), a.clutchRating()));
        return rows;
    }

    // -------------------------------------------------------
    // CSV load
    // -------------------------------------------------------

    private static class BaseGame {
        String gameId;
        String dateRaw;
        String away;
        String home;
        LocalDate parsedDate;

        BaseGame(String gameId, String dateRaw, String away, String home, LocalDate parsedDate) {
            this.gameId = gameId;
            this.dateRaw = dateRaw;
            this.away = away;
            this.home = home;
            this.parsedDate = parsedDate;
        }
    }

    private List<BaseGame> loadPlayedGames(int daysBack) {
        DateTimeFormatter MMM_D_YYYY = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US);
        DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

        LocalDate today = LocalDate.now();
        List<BaseGame> rows = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("static/nba_game_index.csv").getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            br.readLine(); // header
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
                    if (dateRaw.matches("\\d{4}-\\d{2}-\\d{2}")) parsed = LocalDate.parse(dateRaw, ISO);
                    else parsed = LocalDate.parse(dateRaw, MMM_D_YYYY);
                } catch (DateTimeParseException e) {
                    continue;
                }

                // skip future games
                if (parsed.isAfter(today)) continue;

                rows.add(new BaseGame(normalizeGameId(gameId), dateRaw, away, home, parsed));
            }
        } catch (Exception e) {
            return List.of();
        }

        // newest -> oldest
        rows.sort((a, b) -> b.parsedDate.compareTo(a.parsedDate));

        // ALL season
        if (daysBack == 0) return rows;

        // last N unique dates
        Set<LocalDate> keepDates = new LinkedHashSet<>();
        List<BaseGame> out = new ArrayList<>();

        for (BaseGame r : rows) {
            keepDates.add(r.parsedDate);
            if (keepDates.size() > daysBack) break;
            out.add(r);
        }

        return out;
    }

    /**
     * Normalize to 10 digits (NBA CDN format).
     * Example: 22501186 -> 0022501186
     */
    private static String normalizeGameId(String gameId) {
        if (gameId == null) return null;
        String id = gameId.trim();
        if (id.length() == 10) return id;

        if (id.matches("\\d+") && id.length() < 10) {
            return String.format("%10s", id).replace(' ', '0');
        }
        return id;
    }
}
