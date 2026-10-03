package ee.forestiq.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side credentials for Keycloak's Admin REST API. Values are supplied by
 * deployment secrets and must never be exposed to the browser or committed.
 */
@ConfigurationProperties(prefix = "forestiq.keycloak-admin")
public class KeycloakIdentityProperties {

    private String baseUrl = "http://localhost:8180";
    private String realm = "forestiq";
    private String clientId = "forestiq-admin-api";
    private String clientSecret;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public boolean isConfigured() {
        return clientSecret != null && !clientSecret.isBlank();
    }
}
