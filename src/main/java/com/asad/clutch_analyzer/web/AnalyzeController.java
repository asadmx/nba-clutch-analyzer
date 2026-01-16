package com.asad.clutch_analyzer.web;

import com.asad.clutch_analyzer.service.ClutchService;
import com.asad.clutch_analyzer.service.CsvService;
import com.asad.clutch_analyzer.service.SportsApiService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class AnalyzeController {

    private final CsvService csvService;
    private final ClutchService clutchService;
    private final SportsApiService sportsApiService;

    public AnalyzeController(CsvService csvService, ClutchService clutchService, SportsApiService sportsApiService) {
        this.csvService = csvService;
        this.clutchService = clutchService;
        this.sportsApiService = sportsApiService;
    }

    @PostMapping("/analyze")
    public String analyze(@RequestParam("file") MultipartFile file,
                          @RequestParam("clutchSeconds") int clutchSeconds,
                          @RequestParam("teamQuery") String teamQuery,
                          Model model) {

        var events = csvService.parse(file);
        var leaderboard = clutchService.buildLeaderboard(events, clutchSeconds);

        var team = sportsApiService.searchTeamSmart(teamQuery);

        model.addAttribute("isNbaMode", false);
        model.addAttribute("teamQuery", teamQuery);
        model.addAttribute("leaderboard", leaderboard);
        model.addAttribute("teamInfo", team); // may be null if not found

        return "results";
    }
}
