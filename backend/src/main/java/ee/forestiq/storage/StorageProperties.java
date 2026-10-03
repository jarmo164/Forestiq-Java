package ee.forestiq.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forestiq.storage")
public record StorageProperties(
    String endpoint,
    String region,
    String bucket,
    String accessKey,
    String secretKey,
    boolean pathStyleAccess
) { }
