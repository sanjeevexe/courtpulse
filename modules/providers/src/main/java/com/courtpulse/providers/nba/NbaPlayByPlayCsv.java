package com.courtpulse.providers.nba;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the NBA.com live-data play-by-play CSV layout (as published by the open nba_data project)
 * into actions grouped by game and ordered by the provider's {@code orderNumber}. Columns are
 * found by header name, so extra columns are ignored. Quoted fields may contain commas, doubled
 * quotes, and line breaks.
 */
public final class NbaPlayByPlayCsv {
    static final int MAXIMUM_FIELD_LENGTH = 4_096;
    private static final List<String> REQUIRED = List.of(
            "gameId", "actionNumber", "orderNumber", "clock", "timeActual", "period", "actionType",
            "scoreHome", "scoreAway");

    private NbaPlayByPlayCsv() {}

    public static Map<String, List<NbaAction>> read(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        List<String> header = record(reader);
        if (header == null) {
            throw new IllegalArgumentException("Play-by-play CSV is empty");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            columns.putIfAbsent(header.get(index).strip().replace("﻿", ""), index);
        }
        for (String column : REQUIRED) {
            if (!columns.containsKey(column)) {
                throw new IllegalArgumentException("Play-by-play CSV is missing column " + column);
            }
        }
        Map<String, List<NbaAction>> games = new LinkedHashMap<>();
        long line = 1;
        for (List<String> fields = record(reader); fields != null; fields = record(reader)) {
            line++;
            if (fields.size() == 1 && fields.getFirst().isBlank()) {
                continue;
            }
            try {
                NbaAction action = action(fields, columns);
                games.computeIfAbsent(action.nbaGameId(), key -> new ArrayList<>()).add(action);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid play-by-play row near line " + line + ": "
                        + exception.getMessage(), exception);
            }
        }
        games.values().forEach(actions -> actions.sort(Comparator.comparingLong(NbaAction::orderNumber)));
        return games;
    }

    private static NbaAction action(List<String> fields, Map<String, Integer> columns) {
        String gameId = text(fields, columns, "gameId");
        if (!gameId.matches("\\d{1,10}")) {
            throw new IllegalArgumentException("gameId must be numeric");
        }
        return new NbaAction(
                // The CSV drops leading zeros that NBA game IDs carry (0042500101).
                "0".repeat(Math.max(0, 10 - gameId.length())) + gameId,
                number(fields, columns, "actionNumber"),
                number(fields, columns, "orderNumber"),
                text(fields, columns, "clock"),
                text(fields, columns, "timeActual"),
                Math.toIntExact(number(fields, columns, "period")),
                text(fields, columns, "actionType"),
                text(fields, columns, "subType"),
                number(fields, columns, "personId"),
                text(fields, columns, "playerNameI"),
                number(fields, columns, "teamId"),
                text(fields, columns, "teamTricode"),
                Math.toIntExact(number(fields, columns, "scoreHome")),
                Math.toIntExact(number(fields, columns, "scoreAway")),
                text(fields, columns, "shotResult"),
                text(fields, columns, "description"));
    }

    private static String text(List<String> fields, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= fields.size() ? "" : fields.get(index).strip();
    }

    private static long number(List<String> fields, Map<String, Integer> columns, String name) {
        String value = text(fields, columns, name);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            // Some exports write integers as decimals ("1610612760.0").
            return value.contains(".") ? (long) Double.parseDouble(value) : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is not a number");
        }
    }

    /** One RFC 4180 record, or null at end of input. */
    static List<String> record(BufferedReader reader) throws IOException {
        int next = reader.read();
        if (next == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        while (true) {
            if (next == -1) {
                if (quoted) {
                    throw new IllegalArgumentException("Unterminated quoted field");
                }
                fields.add(field.toString());
                return fields;
            }
            char character = (char) next;
            if (quoted) {
                if (character == '"') {
                    reader.mark(1);
                    int following = reader.read();
                    if (following == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (following != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(character);
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (character == '\n') {
                fields.add(field.toString());
                return fields;
            } else if (character != '\r') {
                field.append(character);
            }
            if (field.length() > MAXIMUM_FIELD_LENGTH) {
                throw new IllegalArgumentException("Field longer than " + MAXIMUM_FIELD_LENGTH + " characters");
            }
            next = reader.read();
        }
    }
}
