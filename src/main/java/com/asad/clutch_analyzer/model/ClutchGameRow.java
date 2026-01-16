package com.asad.clutch_analyzer.model;

public class ClutchGameRow {

    // From your CSV index
    public String gameId;
    public String date;   // yyyy-MM-dd
    public String away;   // tricode (e.g., BOS)
    public String home;   // tricode (e.g., NYK)

    // Computed clutch metrics
    public int leadChanges;
    public int ties;
    public int clutchPoints;

    // Score = (10*leadChanges) + (6*ties) + (0.5*clutchPoints)
    public double clutchinessScore;

    // Optional display fields for the UI
    public String finalScore; // e.g., "BOS 112 - 110 NYK"
    public String note;       // e.g., "Not close" / "No PBP" etc.

    public ClutchGameRow() { }

    public ClutchGameRow(String gameId, String date, String away, String home) {
        this.gameId = gameId;
        this.date = date;
        this.away = away;
        this.home = home;
    }
}
