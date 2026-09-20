package com.courtpulse.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

final class PersistenceJson {
    private static final TypeReference<Map<String, Integer>> PLAYER_POINTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    PersistenceJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize durable JSON value", exception);
        }
    }

    List<String> readStringList(String value) {
        return read(value, STRING_LIST);
    }

    Map<String, Integer> readPlayerPoints(String value) {
        return read(value, PLAYER_POINTS);
    }

    Map<String, String> readStringMap(String value) {
        return read(value, STRING_MAP);
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize durable JSON value", exception);
        }
    }
}
