package com.courtpulse.providers.nba;

import java.util.Map;

/** Display names for NBA tricodes; the play-by-play feed carries only codes and numeric IDs. */
public final class NbaTeams {
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("ATL", "Atlanta Hawks"), Map.entry("BOS", "Boston Celtics"),
            Map.entry("BKN", "Brooklyn Nets"), Map.entry("CHA", "Charlotte Hornets"),
            Map.entry("CHI", "Chicago Bulls"), Map.entry("CLE", "Cleveland Cavaliers"),
            Map.entry("DAL", "Dallas Mavericks"), Map.entry("DEN", "Denver Nuggets"),
            Map.entry("DET", "Detroit Pistons"), Map.entry("GSW", "Golden State Warriors"),
            Map.entry("HOU", "Houston Rockets"), Map.entry("IND", "Indiana Pacers"),
            Map.entry("LAC", "LA Clippers"), Map.entry("LAL", "Los Angeles Lakers"),
            Map.entry("MEM", "Memphis Grizzlies"), Map.entry("MIA", "Miami Heat"),
            Map.entry("MIL", "Milwaukee Bucks"), Map.entry("MIN", "Minnesota Timberwolves"),
            Map.entry("NOP", "New Orleans Pelicans"), Map.entry("NYK", "New York Knicks"),
            Map.entry("OKC", "Oklahoma City Thunder"), Map.entry("ORL", "Orlando Magic"),
            Map.entry("PHI", "Philadelphia 76ers"), Map.entry("PHX", "Phoenix Suns"),
            Map.entry("POR", "Portland Trail Blazers"), Map.entry("SAC", "Sacramento Kings"),
            Map.entry("SAS", "San Antonio Spurs"), Map.entry("TOR", "Toronto Raptors"),
            Map.entry("UTA", "Utah Jazz"), Map.entry("WAS", "Washington Wizards"));

    private NbaTeams() {}

    /** The team's name, or the code itself for an unknown (for example fictional) team. */
    public static String name(String tricode) {
        return NAMES.getOrDefault(tricode, tricode);
    }
}
