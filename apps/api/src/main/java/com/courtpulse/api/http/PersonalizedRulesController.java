package com.courtpulse.api.http;

import com.courtpulse.api.rules.PersonalizedRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "oidcBearer")
public class PersonalizedRulesController {
    private final PersonalizedRuleService service;

    public PersonalizedRulesController(PersonalizedRuleService service) {
        this.service = service;
    }

    @Operation(summary = "List alert rules owned by the authenticated user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stable owner-scoped rule page"),
        @ApiResponse(responseCode = "400", description = "Invalid page request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/rules")
    public RuleApiDto.RulePage listRules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor) {
        return service.list(jwt.getSubject(), limit, cursor);
    }

    @Operation(summary = "Create a structured alert rule idempotently")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Identical idempotent request retained",
                headers = @Header(name = "Location", schema = @Schema(type = "string")),
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = RuleApiDto.Rule.class))),
        @ApiResponse(responseCode = "201", description = "Alert rule created",
                headers = @Header(name = "Location", schema = @Schema(type = "string")),
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = RuleApiDto.Rule.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Unknown game"),
        @ApiResponse(responseCode = "409", description = "Idempotency or version conflict"),
        @ApiResponse(responseCode = "422", description = "Rule cannot be accepted"),
        @ApiResponse(responseCode = "429", description = "Rule quota exceeded")
    })
    @PostMapping("/rules")
    public ResponseEntity<RuleApiDto.Rule> createRule(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(required = true, description = "Stable key for safe request replay")
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 100)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String idempotencyKey,
            @Valid @RequestBody RuleApiDto.CreateRuleRequest request) {
        PersonalizedRuleService.Creation result = service.create(
                jwt.getSubject(), idempotencyKey, request);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/api/v1/me/rules/" + result.rule().id()))
                .body(result.rule());
    }

    @Operation(summary = "Read one owned alert rule")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Owned alert rule"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Alert rule not found")
    })
    @GetMapping("/rules/{ruleId}")
    public RuleApiDto.Rule getRule(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID ruleId) {
        return service.get(jwt.getSubject(), ruleId);
    }

    @Operation(summary = "Enable or disable an owned alert rule with optimistic concurrency")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated alert rule"),
        @ApiResponse(responseCode = "400", description = "Malformed request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Alert rule not found"),
        @ApiResponse(responseCode = "409", description = "Version conflict")
    })
    @PatchMapping("/rules/{ruleId}")
    public RuleApiDto.Rule updateRule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID ruleId,
            @Valid @RequestBody RuleApiDto.UpdateRuleRequest request) {
        return service.updateEnabled(jwt.getSubject(), ruleId, request);
    }

    @Operation(summary = "Delete an owned alert rule")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Alert rule deleted"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Alert rule not found")
    })
    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID ruleId) {
        service.delete(jwt.getSubject(), ruleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List private alerts owned by the authenticated user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stable owner-scoped alert page"),
        @ApiResponse(responseCode = "400", description = "Invalid page request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/alerts")
    public RuleApiDto.OwnedAlertPage listAlerts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor) {
        return service.alerts(jwt.getSubject(), limit, cursor);
    }
}
