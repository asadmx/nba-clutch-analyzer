package com.asad.clutch_analyzer.service;

import com.asad.clutch_analyzer.model.GameEvent;
import com.opencsv.CSVReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
public class CsvService {

    public List<GameEvent> parse(MultipartFile file) {
        List<GameEvent> events = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            reader.readNext(); // header
            String[] row;

            while ((row = reader.readNext()) != null) {
                if (row.length < 7) continue;

                GameEvent e = new GameEvent();
                e.date = row[0].trim();
                e.gameId = row[1].trim();
                e.team = row[2].trim();
                e.player = row[3].trim();
                e.quarter = Integer.parseInt(row[4].trim());
                e.clockSeconds = parseClock(row[5].trim());
                e.event = row[6].trim();

                // optional points column
                if (row.length >= 8) {
                    try { e.points = Integer.parseInt(row[7].trim()); } catch (Exception ignored) {}
                }

                events.add(e);
            }
        } catch (Exception ex) {
            throw new RuntimeException("CSV parse failed: " + ex.getMessage());
        }

        return events;
    }

    private int parseClock(String clock) {
        String[] t = clock.split(":");
        int mm = Integer.parseInt(t[0]);
        int ss = Integer.parseInt(t[1]);
        return mm * 60 + ss;
    }
}
