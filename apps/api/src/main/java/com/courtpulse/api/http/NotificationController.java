package com.courtpulse.api.http;

import com.courtpulse.api.notifications.NotificationService;
import com.courtpulse.persistence.NotificationSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(path = "/api/v1/me/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "oidcBearer")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "Read private notification settings")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Owned notification settings"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/settings")
    public NotificationSettings settings(@AuthenticationPrincipal Jwt jwt) {
        return service.settings(jwt.getSubject());
    }

    @Operation(summary = "Update local email opt-in and destination")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated owned settings"),
        @ApiResponse(responseCode = "400", description = "Invalid email settings"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NotificationSettings saveSettings(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateNotificationSettings request) {
        return service.saveSettings(
                jwt.getSubject(), request.emailEnabled(), request.emailAddress());
    }

    @Operation(summary = "Read owned delivery history")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Owned delivery history"),
        @ApiResponse(responseCode = "400", description = "Invalid limit"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/deliveries")
    public DeliveryHistoryPage history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor) {
        var page = service.history(jwt.getSubject(), limit == null ? 20 : limit, cursor);
        return new DeliveryHistoryPage(page.items(), page.nextCursor());
    }

    @Operation(summary = "Read immutable attempts for one owned delivery")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Owned delivery attempts"),
        @ApiResponse(responseCode = "400", description = "Invalid page request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Delivery not found")
    })
    @GetMapping("/deliveries/{deliveryId}/attempts")
    public DeliveryAttemptPage attempts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deliveryId,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor) {
        var page = service.attempts(jwt.getSubject(), deliveryId, limit == null ? 20 : limit, cursor);
        return new DeliveryAttemptPage(page.items(), page.nextCursor());
    }

    public record UpdateNotificationSettings(@NotNull Boolean emailEnabled, @Size(max = 254) String emailAddress) {}
    public record DeliveryHistoryPage(
            java.util.List<com.courtpulse.persistence.DeliveryHistoryRecord> items, String nextCursor) {}
    public record DeliveryAttemptPage(
            java.util.List<com.courtpulse.persistence.DeliveryAttemptRecord> items, String nextCursor) {}
}
