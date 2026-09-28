package com.courtpulse.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtValidationTest {
    private static final String ISSUER = "https://identity.courtpulse.test/realms/courtpulse";
    private static final String AUDIENCE = "courtpulse-api";
    private static final Instant NOW = Instant.now();
    private static final SecretKey KEY = key("court-pulse-test-signing-key-0001");
    private static final SecretKey OTHER_KEY = key("court-pulse-other-signing-key-0002");

    @Test
    void acceptsOnlySignedCurrentIssuerAndAudienceTokens() {
        JwtDecoder decoder = decoder(KEY);
        Jwt valid = decoder.decode(token(KEY, ISSUER, AUDIENCE, NOW.minusSeconds(5), NOW.plusSeconds(300)));
        assertEquals("user-a", valid.getSubject());

        assertThrows(JwtException.class, () -> decoder.decode(
                token(KEY, "https://wrong.example", AUDIENCE, NOW.minusSeconds(5), NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(
                token(KEY, ISSUER, "wrong-audience", NOW.minusSeconds(5), NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(
                token(KEY, ISSUER, AUDIENCE, NOW.minusSeconds(300), NOW.minusSeconds(61))));
        assertThrows(JwtException.class, () -> decoder.decode(
                token(KEY, ISSUER, AUDIENCE, NOW.plusSeconds(120), NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(
                token(OTHER_KEY, ISSUER, AUDIENCE, NOW.minusSeconds(5), NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(unsignedToken()));
    }

    @Test
    void authenticationEnabledWithoutIssuerOrAudienceFailsClosed() {
        CourtPulseAuthenticationProperties properties = new CourtPulseAuthenticationProperties();
        properties.setEnabled(true);
        assertThrows(IllegalStateException.class,
                () -> new SecurityConfiguration().oidcJwtDecoder(properties));
        properties.setIssuerUri(ISSUER);
        assertThrows(IllegalStateException.class,
                () -> new SecurityConfiguration().oidcJwtDecoder(properties));
    }

    @Test
    void authenticationConfigurationRequiresSafePublicAndAuthoritySettings() {
        CourtPulseAuthenticationProperties properties = validProperties();
        SecurityConfiguration.validateEnabledConfiguration(properties);

        properties.setIssuerUri("http://identity.example/realms/courtpulse");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
        properties.setIssuerUri("http://127.0.0.1:8180/realms/courtpulse");
        SecurityConfiguration.validateEnabledConfiguration(properties);

        properties.setBrowserScope("profile email");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
        properties.setBrowserScope("openid profile");
        properties.setClientId(" ");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
        properties.setClientId("courtpulse-web");
        properties.setAuthorityPrefix("");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
        properties.setAuthorityPrefix("ROLE_");
        properties.setJwkSetUri("http://identity:8080/realms/courtpulse/certs?secret=true");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
    }

    @Test
    void cognitoStyleAccessTokensAreMatchedOnClientIdAndTokenUse() {
        var validator = new DelegatingOAuth2TokenValidator<>(
                new AudienceValidator("app-client-123", "client_id"), new TokenUseValidator("access"));
        assertEquals(false, validator.validate(cognito("app-client-123", "access")).hasErrors());
        assertEquals(true, validator.validate(cognito("other-client", "access")).hasErrors(),
                "another app client's token is rejected");
        assertEquals(true, validator.validate(cognito("app-client-123", "id")).hasErrors(),
                "an ID token is not accepted as a bearer access token");
        assertEquals(true, new AudienceValidator("app-client-123", "aud")
                .validate(cognito("app-client-123", "access")).hasErrors(),
                "without an aud claim the default standard check fails closed");
        assertThrows(IllegalArgumentException.class, () -> new AudienceValidator("x", "azp"));
    }

    @Test
    void cognitoConfigurationValidatesClaimChoiceAndLogoutEndpoint() {
        CourtPulseAuthenticationProperties properties = validProperties();
        properties.setIssuerUri("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example");
        properties.setAudienceClaim("client_id");
        properties.setRequiredTokenUse("access");
        properties.setEndSessionEndpoint("https://courtpulse-staging.auth.us-east-1.amazoncognito.com/logout");
        SecurityConfiguration.validateEnabledConfiguration(properties);

        properties.setAudienceClaim("azp");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
        properties.setAudienceClaim("client_id");
        properties.setEndSessionEndpoint("http://courtpulse.auth.example/logout");
        assertThrows(IllegalStateException.class,
                () -> SecurityConfiguration.validateEnabledConfiguration(properties));
    }

    private static Jwt cognito(String clientId, String tokenUse) {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .claim("sub", "user-a")
                .claim("client_id", clientId)
                .claim("token_use", tokenUse)
                .build();
    }

    @Test
    void subjectValidatorRejectsBlankAndOversizedSubjects() {
        SubjectValidator validator = new SubjectValidator();
        assertEquals(false, validator.validate(jwtWithSubject("user-a")).hasErrors());
        assertEquals(true, validator.validate(jwtWithSubject(" ")).hasErrors());
        assertEquals(true, validator.validate(jwtWithSubject("")).hasErrors());
        assertEquals(true, validator.validate(jwtWithSubject("x".repeat(256))).hasErrors());
    }

    private static CourtPulseAuthenticationProperties validProperties() {
        CourtPulseAuthenticationProperties properties = new CourtPulseAuthenticationProperties();
        properties.setEnabled(true);
        properties.setIssuerUri(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setClientId("courtpulse-web");
        properties.setBrowserScope("openid profile");
        properties.setAuthoritiesClaim("courtpulse_roles");
        properties.setAuthorityPrefix("ROLE_");
        properties.setOperationsAuthority("ROLE_courtpulse:ops");
        return properties;
    }

    private static Jwt jwtWithSubject(String subject) {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .claim("sub", subject)
                .build();
    }

    private static JwtDecoder decoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new AudienceValidator(AUDIENCE)));
        return decoder;
    }

    private static String token(
            SecretKey key,
            String issuer,
            String audience,
            Instant notBefore,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user-a")
                .audience(List.of(audience))
                .issuedAt(expiresAt.minusSeconds(600))
                .notBefore(notBefore)
                .expiresAt(expiresAt)
                .claim("scope", "openid")
                .build();
        return NimbusJwtEncoder.withSecretKey(key).build().encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String unsignedToken() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString(("{\"iss\":\"" + ISSUER
                + "\",\"sub\":\"user-a\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":2000000000}").getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }

    private static SecretKey key(String value) {
        return new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
