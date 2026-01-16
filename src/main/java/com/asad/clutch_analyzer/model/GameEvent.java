package com.asad.clutch_analyzer.model;

public class GameEvent {
    public String date;
    public String gameId;
    public String team;
    public String player;
    public int quarter;
    public int clockSeconds;
    public String event;

    // points credited on this event (used for made shots / free throws)
    public int points = 0;

    // NEW: running score at the time of the play (from NBA PBP action)
    // Use Integer so "unknown" is possible without breaking anything.
    public Integer homeScore = null;
    public Integer awayScore = null;

    /**
     * Clutch = last N seconds of the 4th quarter OR any overtime period.
     * clockSeconds is assumed to be time REMAINING in the period.
     */
    public boolean isClutch(int clutchSeconds) {
        if (quarter < 4) return false;          // only 4th + OT
        if (clutchSeconds < 0) return false;
        if (clockSeconds < 0) return false;
        return clockSeconds <= clutchSeconds;   // last N seconds
    }

    /**
     * NEW: Clutch + optional close-game requirement.
     * If closePoints is null, behaves like the original isClutch().
     *
     * If closePoints is provided:
     * - Only count the play if we know the score AND the margin <= closePoints.
     * - If score is missing for the play, we treat it as NOT close (skip it) to avoid bad data.
     */
    public boolean isClutch(int clutchSeconds, Integer closePoints) {
        if (!isClutch(clutchSeconds)) return false;
        if (closePoints == null) return true;
        if (closePoints < 0) return true; // treat negative as "no filter"

        return isCloseGame(closePoints);
    }

    public boolean hasScore() {
        return homeScore != null && awayScore != null;
    }

    public int margin() {
        if (!hasScore()) return Integer.MAX_VALUE;
        return Math.abs(homeScore - awayScore);
    }

    public boolean isCloseGame(int closePoints) {
        if (!hasScore()) return false;
        return margin() <= closePoints;
    }
}
