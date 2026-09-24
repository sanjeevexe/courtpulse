package com.courtpulse.api.http;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.persistence.DeliveryOperations;
import com.courtpulse.persistence.JdbcReconciliationOperationsRepository;
import com.courtpulse.persistence.ReconciliationOperations;
import java.time.Clock;
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
    private final JdbcDeliveryWorkRepository deliveries;
    private final JdbcReconciliationOperationsRepository reconciliations;
    private final Clock clock;

    public OperationsController(CourtPulseQueryService queries, JdbcAlertRuleRepository rules,
            JdbcDeliveryWorkRepository deliveries,
            JdbcReconciliationOperationsRepository reconciliations, Clock clock) {
        this.queries = queries;
        this.rules = rules;
        this.deliveries = deliveries;
        this.reconciliations = reconciliations;
        this.clock = clock;
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

    @Operation(summary = "Read sanitized aggregate alert-delivery health")
    @SecurityRequirement(name = "oidcBearer", scopes = "courtpulse:ops")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Aggregate delivery counts and pending age"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Operational authority required")
    })
    @GetMapping("/deliveries")
    public DeliveryOperations deliveries() {
        return deliveries.operations(clock.instant());
    }

    @Operation(summary = "Read aggregate correction and reconciliation health")
    @SecurityRequirement(name = "oidcBearer", scopes = "courtpulse:ops")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Aggregate reconciliation counts"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Operational authority required")
    })
    @GetMapping("/reconciliations")
    public ReconciliationOperations reconciliations() {
        return reconciliations.summary(clock.instant());
    }
}
