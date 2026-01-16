package com.asad.clutch_analyzer.model;

public class ClutchPlayerRow {

    public String gameId;
    public String date;

    public String player;
    public String team;          // player's team tricode (from play)

    public String away;
    public String home;

    public String finalScore;    // "AWY 112 - 110 HME (OT)"
    public boolean ot;           // true if any OT period occurred

    public String note;          // null if valid, otherwise reason ("No PBP", "Not close")

    // NEW: clutch minutes played in (Q4 last 5:00 + all OT)
    // Example: "5:00", "8:34", "15:00"
    public String clutchMinutes;

    // --- clutch stats (single game, clutch window) ---
    public int points;       // clutch points (made FG + made FT)
    public int assists;
    public int steals;
    public int blocks;
    public int turnovers;
    public int missedFg;
    public int missedFt;

    // Optional: keep this for display if you like (same as points)
    public int clutchPoints;

    public int clutchRating() {
        return points
                + assists
                + 2 * steals
                + 2 * blocks
                - 2 * turnovers
                - missedFg
                - missedFt;
    }

    public ClutchPlayerRow() {}

    public ClutchPlayerRow(String gameId, String date, String away, String home) {
        this.gameId = gameId;
        this.date = date;
        this.away = away;
        this.home = home;
    }
}
