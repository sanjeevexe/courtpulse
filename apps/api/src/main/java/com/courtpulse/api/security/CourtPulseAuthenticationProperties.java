package com.courtpulse.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("courtpulse.auth")
public class CourtPulseAuthenticationProperties {
    private boolean enabled;
    private String issuerUri = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String clientId = "courtpulse-web";
    private String browserScope = "openid profile";
    private String authoritiesClaim = "scope";
    private String authorityPrefix = "SCOPE_";
    private String operationsAuthority = "SCOPE_courtpulse:ops";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getBrowserScope() { return browserScope; }
    public void setBrowserScope(String browserScope) { this.browserScope = browserScope; }
    public String getAuthoritiesClaim() { return authoritiesClaim; }
    public void setAuthoritiesClaim(String authoritiesClaim) { this.authoritiesClaim = authoritiesClaim; }
    public String getAuthorityPrefix() { return authorityPrefix; }
    public void setAuthorityPrefix(String authorityPrefix) { this.authorityPrefix = authorityPrefix; }
    public String getOperationsAuthority() { return operationsAuthority; }
    public void setOperationsAuthority(String operationsAuthority) { this.operationsAuthority = operationsAuthority; }
}
