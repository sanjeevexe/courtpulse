package com.courtpulse.api.security;

import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Standard providers put the API audience in {@code aud}. Amazon Cognito access tokens carry no
 * {@code aud}; they identify the app client in {@code client_id}, so that claim is configurable.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    public static final Set<String> SUPPORTED_CLAIMS = Set.of("aud", "client_id");
    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "The token audience is not accepted", null);
    private final String audience;
    private final String claim;

    public AudienceValidator(String audience) {
        this(audience, "aud");
    }

    public AudienceValidator(String audience, String claim) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience must not be blank");
        }
        if (!SUPPORTED_CLAIMS.contains(claim)) {
            throw new IllegalArgumentException("JWT audience claim must be aud or client_id");
        }
        this.audience = audience;
        this.claim = claim;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        boolean accepted = "aud".equals(claim)
                ? token.getAudience() != null && token.getAudience().contains(audience)
                : audience.equals(token.getClaimAsString(claim));
        return accepted ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
