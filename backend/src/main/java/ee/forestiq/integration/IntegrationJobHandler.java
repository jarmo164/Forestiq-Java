package ee.forestiq.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IntegrationJobHandler {
    IntegrationJobKey key();
    String displayName();
    String provider();
    String purpose();
    String scheduleDescription();
    boolean configured();
    boolean enabled();
    default boolean manualSupported() { return true; }
    List<UUID> scheduledOrganizations();
    IntegrationJobResult run(UUID organizationId, Map<String, String> parameters);
}

