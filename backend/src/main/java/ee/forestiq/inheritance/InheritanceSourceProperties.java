package ee.forestiq.inheritance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forestiq.inheritance-source")
public class InheritanceSourceProperties {
    /** Optional legacy intermediary retained during migration. */
    private String baseUrl = "";
    private String apiToken = "";
    private String uriTemplate = "";
    private String userAgent = "ForestIQ/2.0";
    private String zoneId = "Europe/Tallinn";
    private int connectTimeoutSeconds = 10;
    private int timeoutSeconds = 30;
    private boolean autoRefreshEnabled = false;
    private long autoRefreshFixedDelayMs = 1_800_000L;
    private int autoRefreshBatchSize = 100;
    private int maxBackfillDays = 31;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public String getUriTemplate() { return uriTemplate; }
    public void setUriTemplate(String uriTemplate) { this.uriTemplate = uriTemplate; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isAutoRefreshEnabled() { return autoRefreshEnabled; }
    public void setAutoRefreshEnabled(boolean autoRefreshEnabled) { this.autoRefreshEnabled = autoRefreshEnabled; }
    public long getAutoRefreshFixedDelayMs() { return autoRefreshFixedDelayMs; }
    public void setAutoRefreshFixedDelayMs(long autoRefreshFixedDelayMs) { this.autoRefreshFixedDelayMs = autoRefreshFixedDelayMs; }
    public int getAutoRefreshBatchSize() { return autoRefreshBatchSize; }
    public void setAutoRefreshBatchSize(int autoRefreshBatchSize) { this.autoRefreshBatchSize = autoRefreshBatchSize; }
    public int getMaxBackfillDays() { return maxBackfillDays; }
    public void setMaxBackfillDays(int maxBackfillDays) { this.maxBackfillDays = maxBackfillDays; }

    public boolean isDirectConfigured() { return uriTemplate != null && !uriTemplate.isBlank(); }
    public boolean isLegacyConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiToken != null && !apiToken.isBlank();
    }
    public boolean isConfigured() { return isDirectConfigured() || isLegacyConfigured(); }
}
