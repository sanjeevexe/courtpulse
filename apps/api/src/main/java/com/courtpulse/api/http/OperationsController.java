package com.courtpulse.api.http;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/operations", produces = MediaType.APPLICATION_JSON_VALUE)
public class OperationsController {
    private final CourtPulseQueryService queries;
    private final JdbcAlertRuleRepository rules;

    public OperationsController(CourtPulseQueryService queries, JdbcAlertRuleRepository rules) {
        this.queries = queries;
        this.rules = rules;
    }

    @Operation(summary = "Read sanitized processing and outbox health")
    @SecurityRequirement(name = "oidcBearer", scopes = "courtpulse:ops")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sanitized durable processing counts"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Operational authority required")
    })
    @GetMapping("/processing")
    public ApiDto.Processing processing() {
        return ApiDtoMapper.processing(queries.processingHealth());
    }

    @Operation(summary = "Read sanitized aggregate rule-engine health")
    @SecurityRequirement(name = "oidcBearer", scopes = "courtpulse:ops")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sanitized aggregate rule counts"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Operational authority required")
    })
    @GetMapping("/rules")
    public RuleApiDto.RuleOperations rules() {
        var summary = rules.operationalSummary();
        return new RuleApiDto.RuleOperations(
                summary.ownedRules(), summary.enabledOwnedRules(), summary.disabledOwnedRules(),
                summary.systemRules(), summary.privateAlerts());
    }
}
