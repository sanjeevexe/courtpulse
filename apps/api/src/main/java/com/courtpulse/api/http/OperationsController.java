package com.courtpulse.api.http;

import com.courtpulse.query.CourtPulseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/operations", produces = MediaType.APPLICATION_JSON_VALUE)
public class OperationsController {
    private final CourtPulseQueryService queries;

    public OperationsController(CourtPulseQueryService queries) {
        this.queries = queries;
    }

    @Operation(summary = "Read sanitized processing and outbox health")
    @ApiResponse(responseCode = "200", description = "Sanitized durable processing counts")
    @GetMapping("/processing")
    public ApiDto.Processing processing() {
        return ApiDtoMapper.processing(queries.processingHealth());
    }
}
