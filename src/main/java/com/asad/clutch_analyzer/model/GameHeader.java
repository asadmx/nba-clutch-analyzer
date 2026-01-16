package com.asad.clutch_analyzer.model;

public class GameHeader {

    public String homeTeam;
    public String awayTeam;
    public String gameDate;

    // needed for team logos
    public Integer homeTeamId;
    public Integer awayTeamId;

    // NEW: final score + periods (for FT/OT/2OT...)
    public Integer homeScore;
    public Integer awayScore;
    public Integer periods; // total periods played (4=FT, 5=OT, 6=2OT...)

    public GameHeader() {}

    // Backward-compatible constructor (no IDs / score info)
    public GameHeader(String homeTeam, String awayTeam, String gameDate) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.gameDate = gameDate;
        this.homeTeamId = null;
        this.awayTeamId = null;
        this.homeScore = null;
        this.awayScore = null;
        this.periods = null;
    }

    // Constructor with team IDs (no score info)
    public GameHeader(String homeTeam, String awayTeam, String gameDate,
                      Integer homeTeamId, Integer awayTeamId) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.gameDate = gameDate;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.homeScore = null;
        this.awayScore = null;
        this.periods = null;
    }

    // Full constructor (IDs + score + periods)
    public GameHeader(String homeTeam, String awayTeam, String gameDate,
                      Integer homeTeamId, Integer awayTeamId,
                      Integer homeScore, Integer awayScore, Integer periods) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.gameDate = gameDate;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.periods = periods;
    }
}
