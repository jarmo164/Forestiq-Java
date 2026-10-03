package ee.forestiq.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the one-shot external-data bootstrap process. */
@ConfigurationProperties(prefix = "forestiq.bootstrap")
public class BootstrapProperties {
    private boolean enabled = false;
    private String organizationId = "";
    private int pageSize = 10_000;
    private int maxPagesPerDataset = 100_000;
    private long minRequestIntervalMs = 100L;
    private int inheritanceDays = 365;
    private boolean requirePrivateSources = true;
    private boolean cleanupPreviousSnapshots = true;
    private boolean legacyPublicDatasetsEnabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxPagesPerDataset() { return maxPagesPerDataset; }
    public void setMaxPagesPerDataset(int maxPagesPerDataset) { this.maxPagesPerDataset = maxPagesPerDataset; }
    public long getMinRequestIntervalMs() { return minRequestIntervalMs; }
    public void setMinRequestIntervalMs(long minRequestIntervalMs) { this.minRequestIntervalMs = minRequestIntervalMs; }
    public int getInheritanceDays() { return inheritanceDays; }
    public void setInheritanceDays(int inheritanceDays) { this.inheritanceDays = inheritanceDays; }
    public boolean isRequirePrivateSources() { return requirePrivateSources; }
    public void setRequirePrivateSources(boolean requirePrivateSources) { this.requirePrivateSources = requirePrivateSources; }
    public boolean isCleanupPreviousSnapshots() { return cleanupPreviousSnapshots; }
    public void setCleanupPreviousSnapshots(boolean cleanupPreviousSnapshots) { this.cleanupPreviousSnapshots = cleanupPreviousSnapshots; }
    public boolean isLegacyPublicDatasetsEnabled() { return legacyPublicDatasetsEnabled; }
    public void setLegacyPublicDatasetsEnabled(boolean legacyPublicDatasetsEnabled) {
        this.legacyPublicDatasetsEnabled = legacyPublicDatasetsEnabled;
    }
}
