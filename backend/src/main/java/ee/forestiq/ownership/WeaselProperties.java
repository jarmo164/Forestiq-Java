package ee.forestiq.ownership;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-only configuration for Weasel's authenticated ownership-transition feed. */
@ConfigurationProperties(prefix = "forestiq.weasel")
public class WeaselProperties {
    private String baseUrl = "";
    private String accessToken = "";
    private String organizationId = "";
    private int timeoutSeconds = 15;
    private int maxAttempts = 3;
    private boolean autoRefreshEnabled = false;
    private long autoRefreshFixedDelayMs = 21_600_000L;
    private int pageSize = 100;
    private int maxPagesPerRun = 10;
    private int overlapHours = 6;
    private int initialLookbackDays = 14;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public boolean isAutoRefreshEnabled() { return autoRefreshEnabled; }
    public void setAutoRefreshEnabled(boolean autoRefreshEnabled) { this.autoRefreshEnabled = autoRefreshEnabled; }
    public long getAutoRefreshFixedDelayMs() { return autoRefreshFixedDelayMs; }
    public void setAutoRefreshFixedDelayMs(long autoRefreshFixedDelayMs) { this.autoRefreshFixedDelayMs = autoRefreshFixedDelayMs; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxPagesPerRun() { return maxPagesPerRun; }
    public void setMaxPagesPerRun(int maxPagesPerRun) { this.maxPagesPerRun = maxPagesPerRun; }
    public int getOverlapHours() { return overlapHours; }
    public void setOverlapHours(int overlapHours) { this.overlapHours = overlapHours; }
    public int getInitialLookbackDays() { return initialLookbackDays; }
    public void setInitialLookbackDays(int initialLookbackDays) { this.initialLookbackDays = initialLookbackDays; }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && accessToken != null && !accessToken.isBlank()
            && organizationId != null && !organizationId.isBlank();
    }
}
