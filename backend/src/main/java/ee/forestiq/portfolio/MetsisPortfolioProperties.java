package ee.forestiq.portfolio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-only configuration for the legacy MetsIS/forestek owner-to-cadastre portfolio feed. */
@ConfigurationProperties(prefix = "forestiq.metsis-portfolio")
public class MetsisPortfolioProperties {
    private String uri = "";
    private String authorization = "";
    private String organizationId = "";
    private int timeoutSeconds = 30;
    private int maxAttempts = 3;
    private boolean autoSyncEnabled = false;
    private long autoSyncFixedDelayMs = 86_400_000L;
    private int pageSize = 1_000;
    // The legacy Forestek endpoint controls its own page size. 100 pages proved too
    // small for real production snapshots, so keep a high guard only as protection
    // against a provider pager that never terminates.
    private int maxPagesPerRun = 5_000;

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getAuthorization() { return authorization; }
    public void setAuthorization(String authorization) { this.authorization = authorization; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public boolean isAutoSyncEnabled() { return autoSyncEnabled; }
    public void setAutoSyncEnabled(boolean autoSyncEnabled) { this.autoSyncEnabled = autoSyncEnabled; }
    public long getAutoSyncFixedDelayMs() { return autoSyncFixedDelayMs; }
    public void setAutoSyncFixedDelayMs(long autoSyncFixedDelayMs) { this.autoSyncFixedDelayMs = autoSyncFixedDelayMs; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxPagesPerRun() { return maxPagesPerRun; }
    public void setMaxPagesPerRun(int maxPagesPerRun) { this.maxPagesPerRun = maxPagesPerRun; }

    /** Connection credentials are enough; the tenant can be inferred when ForestIQ has exactly one organization. */
    public boolean isConfigured() {
        return uri != null && !uri.isBlank() && authorization != null && !authorization.isBlank();
    }
}
