package com.asad.clutch_analyzer.model;

public class PlayerStats {

    public String player;   // "K. Durant"
    public String team;     // "HOU"

    public int points = 0;

    public int fgm = 0, fga = 0;
    public int ftm = 0, fta = 0;

    public int ast = 0, stl = 0, blk = 0, tov = 0;

    public PlayerStats(String player, String team) {
        this.player = player;
        this.team = team;
    }

    // Backward compatibility (just in case)
    public PlayerStats(String player) {
        this.player = player;
        this.team = null;
    }

    public void applyEvent(GameEvent e) {

        // ✅ Capture team if not set yet
        if (this.team == null && e.team != null) {
            this.team = e.team;
        }

        switch (e.event) {
            case "SHOT_MADE_2":
            case "SHOT_MADE_3":
                fgm++;
                fga++;
                points += e.points;
                break;

            case "SHOT_MISSED_2":
            case "SHOT_MISSED_3":
                fga++;
                break;

            case "FT_MADE":
                ftm++;
                fta++;
                points += e.points;
                break;

            case "FT_MISSED":
                fta++;
                break;

            case "ASSIST": ast++; break;
            case "STEAL": stl++; break;
            case "BLOCK": blk++; break;
            case "TURNOVER": tov++; break;
        }
    }

    public int clutchRating() {
        int missedFG = fga - fgm;
        int missedFT = fta - ftm;

        return points
                + ast
                + (2 * stl)
                + (2 * blk)
                - (2 * tov)
                - missedFG
                - missedFT;
    }

    // ✅ For UI: "K. Durant (HOU)"
    public String displayName() {
        if (team == null || team.isBlank()) return player;
        return player + " (" + team + ")";
    }
}
