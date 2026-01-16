package com.asad.clutch_analyzer.service;

import com.asad.clutch_analyzer.model.ClutchGameRow;
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
import java.util.stream.Collectors;

@Service
public class ClutchestGamesService {

    private final NbaLiveDataService nbaLiveDataService;

    // -------------------------
    // Caching (big speed-up)
    // -------------------------

    // Cache successful per-game computations for a long time
    private static final long GAME_OK_TTL_MS = 24L * 60 * 60 * 1000; // 24h

    // Cache failures briefly (so we don't spam NBA CDN for games with no PBP yet)
    private static final long GAME_FAIL_TTL_MS = 5L * 60 * 1000; // 5 min

    private static class GameCacheEntry {
        final long createdAtMs;
        final ClutchGameRow row; // includes note if failed
        GameCacheEntry(long createdAtMs, ClutchGameRow row) {
            this.createdAtMs = createdAtMs;
            this.row = row;
        }
    }

    // key = normalized gameId
    private final ConcurrentHashMap<String, GameCacheEntry> gameCache = new ConcurrentHashMap<>();

    // Small request-level cache (days/top) to make quick refreshes instant
    private static final long REQUEST_TTL_MS = 2L * 60 * 1000; // 2 min

    private static class RequestCacheEntry {
        final long createdAtMs;
        final List<ClutchGameRow> rows;
        RequestCacheEntry(long createdAtMs, List<ClutchGameRow> rows) {
            this.createdAtMs = createdAtMs;
            this.rows = rows;
        }
    }

    private final ConcurrentHashMap<String, RequestCacheEntry> requestCache = new ConcurrentHashMap<>();

    public ClutchestGamesService(NbaLiveDataService nbaLiveDataService) {
        this.nbaLiveDataService = nbaLiveDataService;
    }

    /**
     * Returns top clutch games across:
     * - daysBack > 0: last {daysBack} unique game dates
     * - daysBack == 0: ALL season (no limit)
     *
     * Optimized:
     * - Per-game cache (each game fetched/computed once)
     * - Parallel computation
     * - Request cache for quick refreshes
     */
    public List<ClutchGameRow> topClutchestGames(int daysBack, int topN) {

        long now = System.currentTimeMillis();
        String reqKey = "days=" + daysBack + "|top=" + topN;

        RequestCacheEntry reqHit = requestCache.get(reqKey);
        if (reqHit != null && (now - reqHit.createdAtMs) < REQUEST_TTL_MS) {
            return reqHit.rows;
        }

        List<ClutchGameRow> candidates = loadPlayedGames(daysBack);
        if (candidates.isEmpty()) {
            requestCache.put(reqKey, new RequestCacheEntry(now, List.of()));
            return List.of();
        }

        // Thread pool: fast but not insane (avoid hammering NBA CDN)
        int threads = Math.max(6, Math.min(12, Runtime.getRuntime().availableProcessors() * 2));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<ClutchGameRow> cs = new ExecutorCompletionService<>(pool);

        try {
            // Submit jobs only for cache misses / stale entries
            int submitted = 0;

            // We'll keep a topN min-heap as results come in
            PriorityQueue<ClutchGameRow> topHeap = new PriorityQueue<>(Comparator.comparingDouble(r -> r.clutchinessScore));

            // 1) First, consume any fresh cached results immediately
            // 2) Submit compute tasks for the rest
            for (ClutchGameRow base : candidates) {
                String normalizedId = normalizeGameId(base.gameId);

                // Make sure Analyze uses the correct id
                base.gameId = normalizedId;

                GameCacheEntry hit = gameCache.get(normalizedId);
                if (hit != null) {
                    long age = now - hit.createdAtMs;
                    boolean okFresh = hit.row.note == null && age < GAME_OK_TTL_MS;
                    boolean failFresh = hit.row.note != null && age < GAME_FAIL_TTL_MS;

                    if (okFresh) {
                        pushTop(topHeap, cloneForList(hit.row), topN);
                        continue;
                    }
                    if (failFresh) {
                        // still failing recently, skip it for now
                        continue;
                    }
                }

                // Submit compute task
                submitted++;
                cs.submit(() -> computeClutchMetricsFromEvents(base));
            }

            // Collect computed results
            for (int i = 0; i < submitted; i++) {
                Future<ClutchGameRow> f = cs.take();
                ClutchGameRow r;
                try {
                    r = f.get(25, TimeUnit.SECONDS);
                } catch (Exception e) {
                    continue;
                }
                if (r == null) continue;

                // Save to per-game cache (success or failure)
                long savedAt = System.currentTimeMillis();
                gameCache.put(r.gameId, new GameCacheEntry(savedAt, cloneForList(r)));

                // Only keep close-games (per spec)
                if (r.note == null) {
                    pushTop(topHeap, r, topN);
                }
            }

            // Materialize heap -> sorted desc
            List<ClutchGameRow> out = new ArrayList<>(topHeap);
            out.sort((a, b) -> Double.compare(b.clutchinessScore, a.clutchinessScore));

            requestCache.put(reqKey, new RequestCacheEntry(System.currentTimeMillis(), out));
            return out;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void pushTop(PriorityQueue<ClutchGameRow> heap, ClutchGameRow row, int topN) {
        if (row == null) return;
        if (heap.size() < topN) {
            heap.add(row);
            return;
        }
        if (row.clutchinessScore > heap.peek().clutchinessScore) {
            heap.poll();
            heap.add(row);
        }
    }

    private static ClutchGameRow cloneForList(ClutchGameRow r) {
        // shallow copy for safety (avoid accidental mutation)
        ClutchGameRow c = new ClutchGameRow(r.gameId, r.date, r.away, r.home);
        c.leadChanges = r.leadChanges;
        c.ties = r.ties;
        c.clutchPoints = r.clutchPoints;
        c.clutchinessScore = r.clutchinessScore;
        c.finalScore = r.finalScore;
        c.note = r.note;
        return c;
    }

    /**
     * Loads games from nba_game_index.csv (played games only; future dates removed).
     *
     * daysBack:
     *  - 0 => ALL season (no limit)
     *  - >0 => last N unique game dates
     */
    private List<ClutchGameRow> loadPlayedGames(int daysBack) {
        DateTimeFormatter MMM_D_YYYY = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US);
        DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

        LocalDate today = LocalDate.now();

        class Row {
            ClutchGameRow game;
            LocalDate parsedDate;
            Row(ClutchGameRow game, LocalDate parsedDate) {
                this.game = game;
                this.parsedDate = parsedDate;
            }
        }

        List<Row> rows = new ArrayList<>();

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

                // skip future scheduled games
                if (parsed.isAfter(today)) continue;

                rows.add(new Row(new ClutchGameRow(gameId, dateRaw, away, home), parsed));
            }
        } catch (Exception e) {
            return List.of();
        }

        // newest -> oldest
        rows.sort((a, b) -> b.parsedDate.compareTo(a.parsedDate));

        // ALL season
        if (daysBack == 0) {
            List<ClutchGameRow> all = new ArrayList<>(rows.size());
            for (Row r : rows) all.add(r.game);
            return all;
        }

        // last N unique dates
        Set<LocalDate> keepDates = new LinkedHashSet<>();
        List<ClutchGameRow> out = new ArrayList<>();

        for (Row r : rows) {
            keepDates.add(r.parsedDate);
            if (keepDates.size() > daysBack) break;
            out.add(r.game);
        }

        return out;
    }

    // -------------------------------------------------------
    // Metrics from GameEvents
    // -------------------------------------------------------

    private static boolean isClutch(GameEvent e) {
        if (e == null) return false;
        if (e.quarter == 4) return e.clockSeconds <= 300;
        return e.quarter > 4; // OT entire period
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

    private ClutchGameRow computeClutchMetricsFromEvents(ClutchGameRow base) {

        // Normalize id (critical for PBP fetch + Analyze link)
        String normalizedGameId = normalizeGameId(base.gameId);
        base.gameId = normalizedGameId;

        List<GameEvent> events;
        try {
            events = nbaLiveDataService.fetchAndConvertPlayByPlay(normalizedGameId);
        } catch (Exception ex) {
            base.note = "No PBP";
            return base;
        }
        if (events == null || events.isEmpty()) {
            base.note = "No PBP";
            return base;
        }

        // Sort: period asc, clock desc
        events.sort((a, b) -> {
            int p = Integer.compare(a.quarter, b.quarter);
            if (p != 0) return p;
            return Integer.compare(b.clockSeconds, a.clockSeconds);
        });

        int awayScore = 0;
        int homeScore = 0;

        Integer lastLeader = null;      // -1 away, +1 home, 0 tie
        Integer lastNonZeroLeader = null;

        int ties = 0;
        int leadChanges = 0;
        int clutchPoints = 0;

        boolean wasEverCloseInClutch = false;

        for (GameEvent e : events) {
            int pts = e.points;

            if (pts > 0) {
                if (base.home != null && base.home.equalsIgnoreCase(e.team)) homeScore += pts;
                else if (base.away != null && base.away.equalsIgnoreCase(e.team)) awayScore += pts;
            }

            if (!isClutch(e)) continue;

            int margin = homeScore - awayScore;
            if (Math.abs(margin) <= 7) wasEverCloseInClutch = true;

            clutchPoints += pts;

            int leader = sign(margin);

            if (lastLeader == null) {
                lastLeader = leader;
                if (leader != 0) lastNonZeroLeader = leader;
                continue;
            }

            if (leader == 0 && lastLeader != 0) ties++;

            if (leader != 0 && lastLeader != 0 && leader != lastLeader) leadChanges++;

            if (leader != 0 && lastLeader == 0 && lastNonZeroLeader != null && leader != lastNonZeroLeader) {
                leadChanges++;
            }

            lastLeader = leader;
            if (leader != 0) lastNonZeroLeader = leader;
        }

        if (!wasEverCloseInClutch) {
            base.note = "Not close";
            return base;
        }

        base.ties = ties;
        base.leadChanges = leadChanges;
        base.clutchPoints = clutchPoints;

        // Score = (10*leadChanges) + (6*ties) + (0.5*clutchPoints)
        base.clutchinessScore = (10.0 * leadChanges) + (6.0 * ties) + (0.5 * clutchPoints);

        base.finalScore = base.away + " " + awayScore + " - " + homeScore + " " + base.home;

        return base;
    }

    private static int sign(int x) {
        if (x > 0) return 1;
        if (x < 0) return -1;
        return 0;
    }
}
