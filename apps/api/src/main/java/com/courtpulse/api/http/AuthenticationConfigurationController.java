package com.courtpulse.api.http;

import com.courtpulse.api.security.CourtPulseAuthenticationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthenticationConfigurationController {
    private final CourtPulseAuthenticationProperties properties;

    public AuthenticationConfigurationController(CourtPulseAuthenticationProperties properties) {
        this.properties = properties;
    }

    @Operation(summary = "Read public browser OIDC configuration")
    @ApiResponse(
            responseCode = "200",
            description = "Non-secret public client configuration",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiDto.AuthenticationConfiguration.class)))
    @GetMapping("/config")
    public ApiDto.AuthenticationConfiguration configuration() {
        return new ApiDto.AuthenticationConfiguration(
                properties.isEnabled(),
                properties.isEnabled() ? properties.getIssuerUri() : "",
                properties.getClientId(),
                properties.getBrowserScope());
    }
}
