package com.courtpulse.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Human-readable names for the IDs that rules, alerts, and emails carry. Rules and alerts store
 * stable IDs (names can change or arrive later from a provider); text for people is rendered from
 * these names when it is shown or sent, falling back to the ID when a name is not known yet.
 */
public final class JdbcDisplayNames {
    private final JdbcClient jdbc;

    public JdbcDisplayNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Names(Map<String, String> players, Map<String, String> teams, Map<String, String> games) {
        public static final Names NONE = new Names(Map.of(), Map.of(), Map.of());

        public String player(String id) {
            return id == null ? null : players.getOrDefault(id, id);
        }

        public String team(String id) {
            return id == null ? null : teams.getOrDefault(id, id);
        }

        /** "Away at Home" when both teams are named, else the game ID. */
        public String game(String id) {
            return id == null ? null : games.getOrDefault(id, id);
        }
    }

    public Names lookup(Collection<String> playerIds, Collection<String> teamIds, Collection<String> gameIds) {
        return new Names(
                pairs("SELECT id, display_name AS label FROM players WHERE id IN (:ids)", playerIds),
                pairs("SELECT id, COALESCE(name, abbreviation, id) AS label FROM teams WHERE id IN (:ids)", teamIds),
                pairs("""
                        SELECT game.id, away.name || ' at ' || home.name AS label
                        FROM games game
                        JOIN teams home ON home.id = game.home_team_id
                        JOIN teams away ON away.id = game.away_team_id
                        WHERE game.id IN (:ids) AND home.name IS NOT NULL AND away.name IS NOT NULL
                        """, gameIds));
    }

    private Map<String, String> pairs(String sql, Collection<String> ids) {
        List<String> distinct = ids.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(500).toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        jdbc.sql(sql).param("ids", Set.copyOf(distinct))
                .query((row, index) -> Map.entry(row.getString("id"), row.getString("label")))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
