package com.courtpulse.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects ID tokens presented as bearer tokens when the provider labels token use (Cognito). */
public final class TokenUseValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "The token type is not accepted", null);
    private final String requiredTokenUse;

    public TokenUseValidator(String requiredTokenUse) {
        if (requiredTokenUse == null || requiredTokenUse.isBlank()) {
            throw new IllegalArgumentException("Required token use must not be blank");
        }
        this.requiredTokenUse = requiredTokenUse;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return requiredTokenUse.equals(token.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
