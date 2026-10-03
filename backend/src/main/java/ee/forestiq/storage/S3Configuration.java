package ee.forestiq.storage;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Configuration {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint()))
            .region(Region.of(properties.region()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
            .forcePathStyle(properties.pathStyleAccess())
            .build();
    }
}
