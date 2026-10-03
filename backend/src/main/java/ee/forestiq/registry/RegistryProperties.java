package ee.forestiq.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for official external registry interfaces. Public Maa- ja Ruumiamet and
 * Metsaportaal services work with the default values; RIK land-registry access is activated
 * only when the organisation supplies its service-account details.
 */
@ConfigurationProperties(prefix = "forestiq.registry")
public class RegistryProperties {

    private Maaamet maaamet = new Maaamet();
    private Metsaregister metsaregister = new Metsaregister();
    private Rik rik = new Rik();
    private AutoRefresh autoRefresh = new AutoRefresh();
    private Audit audit = new Audit();

    public Maaamet getMaaamet() { return maaamet; }
    public void setMaaamet(Maaamet maaamet) { this.maaamet = maaamet; }
    public Metsaregister getMetsaregister() { return metsaregister; }
    public void setMetsaregister(Metsaregister metsaregister) { this.metsaregister = metsaregister; }
    public Rik getRik() { return rik; }
    public void setRik(Rik rik) { this.rik = rik; }
    public AutoRefresh getAutoRefresh() { return autoRefresh; }
    public void setAutoRefresh(AutoRefresh autoRefresh) { this.autoRefresh = autoRefresh; }
    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }

    public static class Maaamet {
        private String wfsUrl = "https://gsavalik.envir.ee/geoserver/kataster/wfs";
        private String basemapTmsBaseUrl = "https://tiles.maaamet.ee/tm/tms/1.0.0";
        private String inaksUrl = "https://aks.geoportaal.ee/inaks/inaadress/gazetteer";
        private String mapInstitution = "ForestIQ";
        private String mapSystem = "ForestIQ";
        private String mapEnvironment = "local";
        private String mapSourceDate = "";
        private String ortofotoSourceDate = "";
        private String hybridSourceDate = "";
        private String reliefSourceDate = "";
        private boolean reliefWebMercatorEnabled = false;
        private int connectTimeoutSeconds = 15;
        private int maxAttempts = 3;

        public String getWfsUrl() { return wfsUrl; }
        public void setWfsUrl(String wfsUrl) { this.wfsUrl = wfsUrl; }
        public String getBasemapTmsBaseUrl() { return basemapTmsBaseUrl; }
        public void setBasemapTmsBaseUrl(String basemapTmsBaseUrl) { this.basemapTmsBaseUrl = basemapTmsBaseUrl; }
        public String getInaksUrl() { return inaksUrl; }
        public void setInaksUrl(String inaksUrl) { this.inaksUrl = inaksUrl; }
        public String getMapInstitution() { return mapInstitution; }
        public void setMapInstitution(String mapInstitution) { this.mapInstitution = mapInstitution; }
        public String getMapSystem() { return mapSystem; }
        public void setMapSystem(String mapSystem) { this.mapSystem = mapSystem; }
        public String getMapEnvironment() { return mapEnvironment; }
        public void setMapEnvironment(String mapEnvironment) { this.mapEnvironment = mapEnvironment; }
        public String getMapSourceDate() { return mapSourceDate; }
        public void setMapSourceDate(String mapSourceDate) { this.mapSourceDate = mapSourceDate; }
        public String getOrtofotoSourceDate() { return ortofotoSourceDate; }
        public void setOrtofotoSourceDate(String ortofotoSourceDate) { this.ortofotoSourceDate = ortofotoSourceDate; }
        public String getHybridSourceDate() { return hybridSourceDate; }
        public void setHybridSourceDate(String hybridSourceDate) { this.hybridSourceDate = hybridSourceDate; }
        public String getReliefSourceDate() { return reliefSourceDate; }
        public void setReliefSourceDate(String reliefSourceDate) { this.reliefSourceDate = reliefSourceDate; }
        public boolean isReliefWebMercatorEnabled() { return reliefWebMercatorEnabled; }
        public void setReliefWebMercatorEnabled(boolean reliefWebMercatorEnabled) { this.reliefWebMercatorEnabled = reliefWebMercatorEnabled; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class Metsaregister {
        private String wfsUrl = "https://gsavalik.envir.ee/geoserver/metsaregister/ows";
        private int connectTimeoutSeconds = 15;
        private int maxAttempts = 3;
        private int pageSize = 10_000;
        private int maxPagesPerQuery = 1_000;
        private String sortField = "sys_id";
        private int bulkBatchSize = 50;
        private int bulkPagesPerRun = 10;
        private long minRequestIntervalMs = 100;
        public String getWfsUrl() { return wfsUrl; }
        public void setWfsUrl(String wfsUrl) { this.wfsUrl = wfsUrl; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public int getMaxPagesPerQuery() { return maxPagesPerQuery; }
        public void setMaxPagesPerQuery(int maxPagesPerQuery) { this.maxPagesPerQuery = maxPagesPerQuery; }
        public String getSortField() { return sortField; }
        public void setSortField(String sortField) { this.sortField = sortField; }
        public int getBulkBatchSize() { return bulkBatchSize; }
        public void setBulkBatchSize(int bulkBatchSize) { this.bulkBatchSize = bulkBatchSize; }
        public int getBulkPagesPerRun() { return bulkPagesPerRun; }
        public void setBulkPagesPerRun(int bulkPagesPerRun) { this.bulkPagesPerRun = bulkPagesPerRun; }
        public long getMinRequestIntervalMs() { return minRequestIntervalMs; }
        public void setMinRequestIntervalMs(long minRequestIntervalMs) { this.minRequestIntervalMs = minRequestIntervalMs; }
    }

    public static class AutoRefresh {
        private boolean enabled = false;
        private long fixedDelayMs = 21_600_000L;
        private int batchSize = 25;
        private int maxBatchesPerRun = 10;
        private int cadastreMaxAgeHours = 168;
        private int forestMaxAgeHours = 24;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
        public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }
        public int getCadastreMaxAgeHours() { return cadastreMaxAgeHours; }
        public void setCadastreMaxAgeHours(int cadastreMaxAgeHours) { this.cadastreMaxAgeHours = cadastreMaxAgeHours; }
        public int getForestMaxAgeHours() { return forestMaxAgeHours; }
        public void setForestMaxAgeHours(int forestMaxAgeHours) { this.forestMaxAgeHours = forestMaxAgeHours; }
    }

    /** Storage policy for operational registry audit evidence. */
    public static class Audit {
        private int retentionDays = 30;
        private int troubleshootingPayloadRetentionHours = 24;
        private int maxStoredPayloadBytes = 65_536;
        private int maxDiagnosticSampleBytes = 8_192;
        private boolean troubleshootingFullPayloadEnabled = false;
        private long cleanupFixedDelayMs = 21_600_000L;

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getTroubleshootingPayloadRetentionHours() { return troubleshootingPayloadRetentionHours; }
        public void setTroubleshootingPayloadRetentionHours(int value) { this.troubleshootingPayloadRetentionHours = value; }
        public int getMaxStoredPayloadBytes() { return maxStoredPayloadBytes; }
        public void setMaxStoredPayloadBytes(int maxStoredPayloadBytes) { this.maxStoredPayloadBytes = maxStoredPayloadBytes; }
        public int getMaxDiagnosticSampleBytes() { return maxDiagnosticSampleBytes; }
        public void setMaxDiagnosticSampleBytes(int maxDiagnosticSampleBytes) { this.maxDiagnosticSampleBytes = maxDiagnosticSampleBytes; }
        public boolean isTroubleshootingFullPayloadEnabled() { return troubleshootingFullPayloadEnabled; }
        public void setTroubleshootingFullPayloadEnabled(boolean value) { this.troubleshootingFullPayloadEnabled = value; }
        public long getCleanupFixedDelayMs() { return cleanupFixedDelayMs; }
        public void setCleanupFixedDelayMs(long cleanupFixedDelayMs) { this.cleanupFixedDelayMs = cleanupFixedDelayMs; }
    }

    public static class Rik {
        private String soapWsdlUrl = "https://kinnistusraamat.rik.ee/krteenused/kr_teenus.asmx?WSDL";
        private String username;
        private String password;
        public String getSoapWsdlUrl() { return soapWsdlUrl; }
        public void setSoapWsdlUrl(String soapWsdlUrl) { this.soapWsdlUrl = soapWsdlUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isConfigured() { return username != null && !username.isBlank() && password != null && !password.isBlank(); }
    }
}
