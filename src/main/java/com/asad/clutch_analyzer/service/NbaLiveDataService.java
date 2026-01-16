package com.asad.clutch_analyzer.service;

import com.asad.clutch_analyzer.model.GameEvent;
import com.asad.clutch_analyzer.model.GameHeader;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NbaLiveDataService {

    private final RestClient client = RestClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept", "application/json")
            .build();

    private static final String PBP_URL =
            "https://cdn.nba.com/static/json/liveData/playbyplay/playbyplay_%s.json";

    private static final String BOXSCORE_URL =
            "https://cdn.nba.com/static/json/liveData/boxscore/boxscore_%s.json";

    private static final String SCOREBOARD_URL =
            "https://cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_%s.json";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final Pattern AST_PAREN_PATTERN =
            Pattern.compile("\\(([^()]+?)\\s+\\d+\\s+AST\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCORE_PAIR_PATTERN =
            Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");

    // NEW: substitution parsing from description
    // Examples often look like:
    // "SUB: A. Edwards enters the game for M. Conley"
    // "A. Edwards enters the game for M. Conley"
    private static final Pattern SUB_PATTERN =
            Pattern.compile("(?:SUB:\\s*)?(.+?)\\s+enters the game for\\s+(.+)", Pattern.CASE_INSENSITIVE);

    // ---------------- HEADER ----------------

    public GameHeader fetchGameHeader(String gameId) {
        String gid = normalizeGameId(gameId);
        if (gid == null || gid.isBlank()) {
            return new GameHeader("Unknown", "Unknown", "Unknown", null, null);
        }

        GameHeader fromPbp = fetchHeaderFromPlayByPlay(gid);
        if (fromPbp != null) {
            applyScoreAndPeriodsFromBoxscore(gid, fromPbp);
            return fromPbp;
        }

        GameHeader fromBox = fetchHeaderFromBoxscore(gid);
        if (fromBox != null) {
            applyScoreAndPeriodsFromBoxscore(gid, fromBox);
            return fromBox;
        }

        String dateFromInput = extractDateFromGameId(gameId);
        if (dateFromInput != null) {
            GameHeader fromBoard = fetchHeaderFromScoreboard(gid, dateFromInput);
            if (fromBoard != null) {
                applyScoreAndPeriodsFromBoxscore(gid, fromBoard);
                return fromBoard;
            }
            return new GameHeader("Unknown", "Unknown", dateFromInput, null, null);
        }

        LocalDate today = LocalDate.now();
        for (int i = 0; i <= 14; i++) {
            String date = today.minusDays(i).format(YYYYMMDD);
            GameHeader fromBoard = fetchHeaderFromScoreboard(gid, date);
            if (fromBoard != null) {
                applyScoreAndPeriodsFromBoxscore(gid, fromBoard);
                return fromBoard;
            }
        }

        return new GameHeader("Unknown", "Unknown", "Unknown", null, null);
    }

    @SuppressWarnings("unchecked")
    private GameHeader fetchHeaderFromPlayByPlay(String gameId) {
        String url = String.format(PBP_URL, gameId);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return null;
        }
        if (root == null) return null;

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return null;

        Map<String, Object> home = (Map<String, Object>) game.get("homeTeam");
        Map<String, Object> away = (Map<String, Object>) game.get("awayTeam");

        String homeTeam = formatTeam(home);
        String awayTeam = formatTeam(away);

        Integer homeTeamId = asInteger(home != null ? home.get("teamId") : null);
        Integer awayTeamId = asInteger(away != null ? away.get("teamId") : null);

        String gameDate = firstNonEmpty(
                asString(game.get("gameEt")),
                asString(game.get("gameTimeUTC")),
                asString(game.get("gameDate")),
                asString(game.get("gameTimeLocal")),
                asString(game.get("gameDateEst"))
        );

        if ("Unknown".equals(homeTeam) && "Unknown".equals(awayTeam)) return null;

        GameHeader h = new GameHeader(
                homeTeam,
                awayTeam,
                gameDate != null ? gameDate : "Unknown",
                homeTeamId,
                awayTeamId
        );

        try {
            if (home != null) h.homeScore = asInteger(home.get("score"));
            if (away != null) h.awayScore = asInteger(away.get("score"));
            h.periods = asInteger(game.get("period"));
        } catch (Exception ignored) {}

        return h;
    }

    @SuppressWarnings("unchecked")
    private GameHeader fetchHeaderFromBoxscore(String gameId) {
        String url = String.format(BOXSCORE_URL, gameId);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return null;
        }
        if (root == null) return null;

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return null;

        Map<String, Object> home = (Map<String, Object>) game.get("homeTeam");
        Map<String, Object> away = (Map<String, Object>) game.get("awayTeam");

        String homeTeam = formatTeam(home);
        String awayTeam = formatTeam(away);

        Integer homeTeamId = asInteger(home != null ? home.get("teamId") : null);
        Integer awayTeamId = asInteger(away != null ? away.get("teamId") : null);

        String gameDate = firstNonEmpty(
                asString(game.get("gameEt")),
                asString(game.get("gameTimeUTC")),
                asString(game.get("gameDate")),
                asString(game.get("gameTimeLocal")),
                asString(game.get("gameDateEst"))
        );

        if ("Unknown".equals(homeTeam) && "Unknown".equals(awayTeam)) return null;

        GameHeader h = new GameHeader(
                homeTeam,
                awayTeam,
                gameDate != null ? gameDate : "Unknown",
                homeTeamId,
                awayTeamId
        );

        try {
            if (home != null) h.homeScore = asInteger(home.get("score"));
            if (away != null) h.awayScore = asInteger(away.get("score"));
            h.periods = asInteger(game.get("period"));
        } catch (Exception ignored) {}

        return h;
    }

    @SuppressWarnings("unchecked")
    private GameHeader fetchHeaderFromScoreboard(String gameId, String yyyymmdd) {
        String url = String.format(SCOREBOARD_URL, yyyymmdd);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return null;
        }
        if (root == null) return null;

        Map<String, Object> scoreboard = (Map<String, Object>) root.get("scoreboard");
        if (scoreboard == null) return null;

        List<Object> games = (List<Object>) scoreboard.get("games");
        if (games == null) return null;

        for (Object gObj : games) {
            if (!(gObj instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) gObj;

            String id = asString(g.get("gameId"));
            if (id == null) continue;

            boolean matchesExact = id.equals(gameId);
            boolean matchesSuffix = gameId.endsWith(id) || id.endsWith(gameId);
            if (!matchesExact && !matchesSuffix) continue;

            Map<String, Object> home = (Map<String, Object>) g.get("homeTeam");
            Map<String, Object> away = (Map<String, Object>) g.get("awayTeam");

            String homeTeam = formatTeam(home);
            String awayTeam = formatTeam(away);

            Integer homeTeamId = asInteger(home != null ? home.get("teamId") : null);
            Integer awayTeamId = asInteger(away != null ? away.get("teamId") : null);

            String gameDate = firstNonEmpty(
                    asString(g.get("gameEt")),
                    asString(g.get("gameTimeUTC")),
                    asString(g.get("gameDate")),
                    asString(g.get("gameDateEst")),
                    yyyymmdd
            );

            GameHeader h = new GameHeader(
                    homeTeam,
                    awayTeam,
                    gameDate != null ? gameDate : "Unknown",
                    homeTeamId,
                    awayTeamId
            );

            try {
                if (home != null) h.homeScore = asInteger(home.get("score"));
                if (away != null) h.awayScore = asInteger(away.get("score"));
                h.periods = asInteger(g.get("period"));
            } catch (Exception ignored) {}

            return h;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private void applyScoreAndPeriodsFromBoxscore(String gid, GameHeader header) {
        if (header == null) return;

        boolean hasScores = header.homeScore != null && header.awayScore != null;
        boolean hasPeriods = header.periods != null;
        if (hasScores && hasPeriods) return;

        String url = String.format(BOXSCORE_URL, gid);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return;
        }
        if (root == null) return;

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return;

        Map<String, Object> home = (Map<String, Object>) game.get("homeTeam");
        Map<String, Object> away = (Map<String, Object>) game.get("awayTeam");

        if (home != null) {
            if (header.homeScore == null) header.homeScore = asInteger(home.get("score"));
            if (header.homeTeamId == null) header.homeTeamId = asInteger(home.get("teamId"));
            if (header.homeTeam == null || header.homeTeam.isBlank() || "Unknown".equalsIgnoreCase(header.homeTeam)) {
                String ht = firstNonEmpty(asString(home.get("teamTricode")), asString(home.get("tricode")));
                if (ht != null) header.homeTeam = ht;
            }
        }

        if (away != null) {
            if (header.awayScore == null) header.awayScore = asInteger(away.get("score"));
            if (header.awayTeamId == null) header.awayTeamId = asInteger(away.get("teamId"));
            if (header.awayTeam == null || header.awayTeam.isBlank() || "Unknown".equalsIgnoreCase(header.awayTeam)) {
                String at = firstNonEmpty(asString(away.get("teamTricode")), asString(away.get("tricode")));
                if (at != null) header.awayTeam = at;
            }
        }

        if (header.periods == null) {
            header.periods = asInteger(game.get("period"));
        }

        if (header.gameDate == null || header.gameDate.isBlank() || "Unknown".equalsIgnoreCase(header.gameDate)) {
            String d = firstNonEmpty(asString(game.get("gameDateEst")), asString(game.get("gameDate")));
            if (d != null) header.gameDate = (d.length() >= 10 ? d.substring(0, 10) : d);
        }
    }

    // ---------------- CLUTCH MINUTES (NEW) ----------------

    /**
     * NEW: Returns clutch seconds played per player (keyed by normalized player name key).
     *
     * Clutch time definition:
     * - Q4: last 5:00 (clock <= 300)
     * - OT: entire OT periods
     *
     * Uses starters from boxscore + substitution descriptions from PBP.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> fetchClutchSecondsByPlayerName(String gameId) {
        String gid = normalizeGameId(gameId);
        if (gid == null || gid.isBlank()) return Map.of();

        // 1) starters from boxscore
        Map<String, Set<String>> onCourt = fetchStartersOnCourtByTeamKey(gid);
        if (onCourt.isEmpty()) {
            // If we can't establish on-court, we can't compute minutes reliably.
            return Map.of();
        }

        // 2) get raw PBP actions
        Map<String, Object> root;
        try {
            root = client.get().uri(String.format(PBP_URL, gid)).retrieve().body(Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
        if (root == null) return Map.of();

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return Map.of();

        List<Object> actions = (List<Object>) game.get("actions");
        if (actions == null || actions.isEmpty()) return Map.of();

        // Convert actions into a list we can sort
        class A {
            int period;
            int clockSec;
            String teamTri;
            String desc;
            String actionType;
            A(int period, int clockSec, String teamTri, String desc, String actionType){
                this.period = period;
                this.clockSec = clockSec;
                this.teamTri = teamTri;
                this.desc = desc;
                this.actionType = actionType;
            }
        }

        List<A> list = new ArrayList<>();
        for (Object o : actions) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> a = (Map<String, Object>) o;

            Integer p = asInt(a.get("period"));
            String clock = asString(a.get("clock"));
            if (p == null || clock == null) continue;

            int cs = parseClock(clock);
            String teamTri = asString(a.get("teamTricode"));
            String desc = asString(a.get("description"));
            String actionType = asString(a.get("actionType"));

            list.add(new A(p, cs, teamTri, desc, actionType));
        }

        // Sort chronological: period asc, clock desc (12:00 -> 0:00)
        list.sort((x, y) -> {
            int c = Integer.compare(x.period, y.period);
            if (c != 0) return c;
            return Integer.compare(y.clockSec, x.clockSec);
        });

        Map<String, Integer> secondsByPlayer = new HashMap<>();

        int currentPeriod = 1;
        int periodLen = periodLengthSeconds(currentPeriod);
        int lastClock = periodLen;

        for (A a : list) {
            // advance periods if we jumped
            while (currentPeriod < a.period) {
                // finish remaining segment in this period (lastClock -> 0)
                addClutchOverlap(secondsByPlayer, onCourt, currentPeriod, 0, lastClock);

                currentPeriod++;
                periodLen = periodLengthSeconds(currentPeriod);
                lastClock = periodLen; // reset for next period
            }

            // same period: accumulate time segment between lastClock and this action clock
            int cur = Math.max(0, Math.min(lastClock, a.clockSec));
            addClutchOverlap(secondsByPlayer, onCourt, currentPeriod, cur, lastClock);

            // apply substitution at this exact timestamp
            if (isSubEvent(a.actionType, a.desc)) {
                applySubstitution(onCourt, a.teamTri, a.desc);
            }

            lastClock = cur;
        }

        // finish remainder of last period
        addClutchOverlap(secondsByPlayer, onCourt, currentPeriod, 0, lastClock);

        return secondsByPlayer;
    }

    private boolean isSubEvent(String actionType, String desc) {
        if (desc == null) return false;
        if (actionType != null && actionType.toLowerCase(Locale.ROOT).contains("sub")) return true;
        return desc.toLowerCase(Locale.ROOT).contains("enters the game for");
    }

    private void applySubstitution(Map<String, Set<String>> onCourt, String teamTri, String desc) {
        if (teamTri == null || teamTri.isBlank() || desc == null) return;

        Matcher m = SUB_PATTERN.matcher(desc);
        if (!m.find()) return;

        String inName = m.group(1) != null ? m.group(1).trim() : null;
        String outName = m.group(2) != null ? m.group(2).trim() : null;
        if (inName == null || outName == null) return;

        String teamKey = normalizeTeamKey(teamTri);
        Set<String> five = onCourt.computeIfAbsent(teamKey, k -> new HashSet<>());

        String inKey = normalizeNameKey(inName);
        String outKey = normalizeNameKey(outName);

        // remove out, add in
        if (outKey != null) five.remove(outKey);
        if (inKey != null) five.add(inKey);

        // keep sanity if feed ever duplicates:
        // (don’t hard-enforce size 5, but usually it stays 5)
    }

    private void addClutchOverlap(Map<String, Integer> secondsByPlayer,
                                  Map<String, Set<String>> onCourt,
                                  int period,
                                  int curClock,
                                  int lastClock) {

        if (!isClutchPeriod(period)) return;

        int clutchMax = clutchMaxClock(period); // in "seconds remaining" space
        int clutchMin = 0;

        // segment covers remaining-clock range (curClock .. lastClock]
        int start = Math.max(curClock, clutchMin);
        int end = Math.min(lastClock, clutchMax);

        int overlap = Math.max(0, end - start);
        if (overlap <= 0) return;

        // add overlap to every player currently on court for both teams
        for (Set<String> five : onCourt.values()) {
            for (String pKey : five) {
                if (pKey == null) continue;
                secondsByPlayer.merge(pKey, overlap, Integer::sum);
            }
        }
    }

    private boolean isClutchPeriod(int period) {
        return period == 4 || period > 4;
    }

    private int clutchMaxClock(int period) {
        if (period == 4) return 300;        // last 5:00 only
        return periodLengthSeconds(period); // entire OT period
    }

    private int periodLengthSeconds(int period) {
        if (period >= 1 && period <= 4) return 12 * 60;
        return 5 * 60; // OT
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> fetchStartersOnCourtByTeamKey(String gameId) {
        String url = String.format(BOXSCORE_URL, gameId);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
        if (root == null) return Map.of();

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return Map.of();

        Map<String, Set<String>> out = new HashMap<>();

        Map<String, Object> home = (Map<String, Object>) game.get("homeTeam");
        Map<String, Object> away = (Map<String, Object>) game.get("awayTeam");

        putStarters(home, out);
        putStarters(away, out);

        // if either team couldn't be built, return empty to avoid bogus mins
        if (out.size() < 2) return Map.of();
        return out;
    }

    @SuppressWarnings("unchecked")
    private void putStarters(Map<String, Object> team, Map<String, Set<String>> out) {
        if (team == null) return;

        String tri = firstNonEmpty(asString(team.get("teamTricode")), asString(team.get("tricode")));
        if (tri == null) return;

        Object playersObj = team.get("players");
        if (!(playersObj instanceof List)) return;

        Set<String> starters = new HashSet<>();

        for (Object pObj : (List<Object>) playersObj) {
            if (!(pObj instanceof Map)) continue;
            Map<String, Object> p = (Map<String, Object>) pObj;

            // starter flag varies
            Boolean starterBool = asBool(p.get("starter"));
            Integer starterInt = asInteger(p.get("starter"));

            boolean isStarter =
                    Boolean.TRUE.equals(starterBool) ||
                            (starterInt != null && starterInt == 1) ||
                            "1".equals(asString(p.get("starter"))) ||
                            "true".equalsIgnoreCase(asString(p.get("starter")));

            if (!isStarter) continue;

            String nameI = firstNonEmpty(asString(p.get("nameI")), asString(p.get("playerNameI")));
            String name = firstNonEmpty(asString(p.get("name")), asString(p.get("playerName")));

            String pick = (nameI != null) ? nameI : name;
            if (pick == null) continue;

            starters.add(normalizeNameKey(pick));
        }

        if (starters.size() >= 5) {
            out.put(normalizeTeamKey(tri), starters);
        }
    }

    private String normalizeTeamKey(String s) {
        if (s == null) return null;
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNameKey(String s) {
        if (s == null) return null;
        return s.trim()
                .toLowerCase(Locale.ROOT)
                .replace(".", "")
                .replace("’", "'")
                .replace("'", "")
                .replace("-", " ")
                .replaceAll("\\s+", " ");
    }

    // ---------------- SCORE ATTACHMENT ----------------

    @SuppressWarnings("unchecked")
    private void attachScore(GameEvent ev, Map<String, Object> a) {
        if (ev == null || a == null) return;

        Integer home = firstInt(
                a.get("scoreHome"),
                a.get("homeScore"),
                a.get("homeTeamScore"),
                a.get("homeScoreTotal")
        );

        Integer away = firstInt(
                a.get("scoreAway"),
                a.get("awayScore"),
                a.get("awayTeamScore"),
                a.get("awayScoreTotal")
        );

        if (home != null && away != null) {
            ev.homeScore = home;
            ev.awayScore = away;
            return;
        }

        String scoreStr = asString(a.get("score"));
        if (scoreStr == null) scoreStr = asString(a.get("scoreString"));
        if (scoreStr == null) scoreStr = asString(a.get("scoreDisplay"));

        if (scoreStr != null) {
            Matcher m = SCORE_PAIR_PATTERN.matcher(scoreStr);
            if (m.find()) {
                try {
                    Integer s1 = Integer.parseInt(m.group(1));
                    Integer s2 = Integer.parseInt(m.group(2));
                    ev.homeScore = s1;
                    ev.awayScore = s2;
                    return;
                } catch (Exception ignored) {}
            }
        }

        try {
            Object ht = a.get("homeTeam");
            Object at = a.get("awayTeam");
            if (ht instanceof Map && at instanceof Map) {
                Integer hs = asInteger(((Map<String, Object>) ht).get("score"));
                Integer as = asInteger(((Map<String, Object>) at).get("score"));
                if (hs != null && as != null) {
                    ev.homeScore = hs;
                    ev.awayScore = as;
                }
            }
        } catch (Exception ignored) {}
    }

    private Integer firstInt(Object... objs) {
        for (Object o : objs) {
            Integer i = asInteger(o);
            if (i != null) return i;
        }
        return null;
    }

    // ---------------- PLAY BY PLAY ----------------

    @SuppressWarnings("unchecked")
    public List<GameEvent> fetchAndConvertPlayByPlay(String gameId) {
        String gid = normalizeGameId(gameId);
        if (gid == null || gid.isBlank()) return List.of();

        String url = String.format(PBP_URL, gid);

        Map<String, Object> root;
        try {
            root = client.get().uri(url).retrieve().body(Map.class);
        } catch (Exception ex) {
            return List.of();
        }
        if (root == null) return List.of();

        Map<String, Object> game = (Map<String, Object>) root.get("game");
        if (game == null) return List.of();

        List<Object> actions = (List<Object>) game.get("actions");
        if (actions == null) return List.of();

        List<GameEvent> out = new ArrayList<>();

        for (Object obj : actions) {
            if (!(obj instanceof Map)) continue;
            Map<String, Object> a = (Map<String, Object>) obj;

            Integer period = asInt(a.get("period"));
            String clock = asString(a.get("clock"));
            String desc = asString(a.get("description"));

            String teamTri = asString(a.get("teamTricode"));

            String shotResult = asString(a.get("shotResult"));
            String shotType = asString(a.get("shotType"));
            Boolean isFieldGoal = asBool(a.get("isFieldGoal"));

            String shooter = firstNonEmpty(
                    asString(a.get("playerNameI")),
                    asString(a.get("playerName"))
            );
            if (shooter == null) continue;

            boolean made = "Made".equalsIgnoreCase(shotResult) || contains(desc, "makes");
            boolean missed = "Missed".equalsIgnoreCase(shotResult) || contains(desc, "misses");

            boolean isFreeThrow = contains(desc, "free throw");
            if (isFreeThrow && (made || missed)) {
                GameEvent ft = new GameEvent();
                ft.gameId = gid;
                ft.player = shooter;
                ft.team = teamTri;
                ft.quarter = (period != null ? period : 0);
                ft.clockSeconds = parseClock(clock);

                if (made) {
                    ft.event = "FT_MADE";
                    ft.points = 1;
                } else {
                    ft.event = "FT_MISSED";
                    ft.points = 0;
                }

                attachScore(ft, a);

                out.add(ft);
                continue;
            }

            boolean isFgAction = (Boolean.TRUE.equals(isFieldGoal) || (isFieldGoal == null && (made || missed)));

            if (isFgAction && (made || missed)) {
                boolean is3 =
                        contains(shotType, "3") ||
                                contains(desc, "3PT") ||
                                contains(desc, "3-PT") ||
                                contains(desc, "three");

                GameEvent shot = new GameEvent();
                shot.gameId = gid;
                shot.player = shooter;
                shot.team = teamTri;
                shot.quarter = (period != null ? period : 0);
                shot.clockSeconds = parseClock(clock);

                if (made) {
                    shot.event = is3 ? "SHOT_MADE_3" : "SHOT_MADE_2";
                    shot.points = is3 ? 3 : 2;

                    attachScore(shot, a);

                    out.add(shot);

                    String assister = extractAssister(a, desc);
                    if (assister != null && !assister.equalsIgnoreCase(shooter)) {
                        GameEvent ast = new GameEvent();
                        ast.gameId = gid;
                        ast.player = assister;
                        ast.team = teamTri;
                        ast.quarter = shot.quarter;
                        ast.clockSeconds = shot.clockSeconds;
                        ast.event = "ASSIST";
                        ast.points = 0;

                        ast.homeScore = shot.homeScore;
                        ast.awayScore = shot.awayScore;

                        out.add(ast);
                    }

                } else {
                    shot.event = is3 ? "SHOT_MISSED_3" : "SHOT_MISSED_2";
                    shot.points = 0;

                    attachScore(shot, a);

                    out.add(shot);
                }

                continue;
            }

            String actionType = asString(a.get("actionType"));

            if (contains(actionType, "turnover") || contains(desc, "turnover")) {
                GameEvent ev = new GameEvent();
                ev.gameId = gid;
                ev.player = shooter;
                ev.team = teamTri;
                ev.quarter = (period != null ? period : 0);
                ev.clockSeconds = parseClock(clock);
                ev.event = "TURNOVER";
                ev.points = 0;

                attachScore(ev, a);

                out.add(ev);

            } else if (contains(actionType, "steal") || contains(desc, "steal")) {
                GameEvent ev = new GameEvent();
                ev.gameId = gid;
                ev.player = shooter;
                ev.team = teamTri;
                ev.quarter = (period != null ? period : 0);
                ev.clockSeconds = parseClock(clock);
                ev.event = "STEAL";
                ev.points = 0;

                attachScore(ev, a);

                out.add(ev);

            } else if (contains(actionType, "block") || contains(desc, "block")) {
                GameEvent ev = new GameEvent();
                ev.gameId = gid;
                ev.player = shooter;
                ev.team = teamTri;
                ev.quarter = (period != null ? period : 0);
                ev.clockSeconds = parseClock(clock);
                ev.event = "BLOCK";
                ev.points = 0;

                attachScore(ev, a);

                out.add(ev);
            }
        }

        return out;
    }

    private String extractAssister(Map<String, Object> a, String desc) {
        String v = firstNonEmpty(
                asString(a.get("assistPlayerNameInitial")),
                asString(a.get("assistPlayerName")),
                asString(a.get("assistName"))
        );
        if (v != null) return v;

        if (desc != null) {
            Matcher m = AST_PAREN_PATTERN.matcher(desc);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    private int parseClock(String clock) {
        if (clock == null || clock.isBlank()) return 0;

        String c = clock.trim();

        if (c.contains(":")) {
            String[] t = c.split(":");
            if (t.length >= 2) {
                try {
                    int mm = Integer.parseInt(t[0]);
                    int ss = Integer.parseInt(t[1]);
                    return mm * 60 + ss;
                } catch (Exception ignored) {
                    return 0;
                }
            }
        }

        try {
            int minutes = 0;
            int seconds = 0;

            int mIndex = c.indexOf('M');
            if (c.startsWith("PT") && mIndex > 2) {
                String mStr = c.substring(2, mIndex);
                minutes = Integer.parseInt(mStr);
            }

            int sIndex = c.indexOf('S');
            if (mIndex >= 0 && sIndex > mIndex) {
                String sStr = c.substring(mIndex + 1, sIndex);
                if (sStr.contains(".")) sStr = sStr.substring(0, sStr.indexOf('.'));
                seconds = Integer.parseInt(sStr);
            }

            return minutes * 60 + seconds;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String normalizeGameId(String gameId) {
        if (gameId == null) return null;
        String g = gameId.trim();
        int slash = g.lastIndexOf('/');
        if (slash >= 0 && slash < g.length() - 1) {
            return g.substring(slash + 1);
        }
        return g;
    }

    private String extractDateFromGameId(String gameId) {
        if (gameId == null) return null;
        String cleaned = gameId.trim();

        int slash = cleaned.indexOf('/');
        if (slash >= 8) {
            String maybe = cleaned.substring(0, 8);
            if (looksLikeYYYYMMDD(maybe)) return maybe;
        }

        if (cleaned.length() >= 8) {
            String maybe = cleaned.substring(0, 8);
            if (looksLikeYYYYMMDD(maybe)) return maybe;
        }

        return null;
    }

    private boolean looksLikeYYYYMMDD(String s) {
        if (s == null || !s.matches("\\d{8}")) return false;
        if (!s.startsWith("20")) return false;

        int month = Integer.parseInt(s.substring(4, 6));
        int day = Integer.parseInt(s.substring(6, 8));

        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    private String formatTeam(Map<String, Object> t) {
        if (t == null) return "Unknown";

        String city = firstNonEmpty(
                asString(t.get("teamCity")),
                asString(t.get("city"))
        );

        String name = firstNonEmpty(
                asString(t.get("teamName")),
                asString(t.get("name")),
                asString(t.get("nickname"))
        );

        String tri = firstNonEmpty(
                asString(t.get("teamTricode")),
                asString(t.get("tricode")),
                asString(t.get("abbreviation"))
        );

        if (city != null && name != null) return (city + " " + name).trim();
        if (name != null) return name.trim();
        if (tri != null) return tri.trim();
        return "Unknown";
    }

    private Integer asInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Long l) return (int) l.longValue();
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    private Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim().toLowerCase();
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        return null;
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }

    private boolean contains(String s, String sub) {
        return s != null && sub != null && s.toLowerCase().contains(sub.toLowerCase());
    }
}
