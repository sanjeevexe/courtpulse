package com.courtpulse.api.security;

import com.courtpulse.api.http.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;

public final class SecurityProblemWriter {
    private final ObjectMapper mapper;

    public SecurityProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 401, "authentication_required", "Authentication required",
                "A valid bearer token is required for this resource.");
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 403, "insufficient_authority", "Access denied",
                "The authenticated identity is not permitted to use this resource.");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String title,
            String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://courtpulse.dev/problems/" + code);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("correlationId", correlationId(request));
        problem.put("code", code);
        mapper.writeValue(response.getOutputStream(), problem);
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
