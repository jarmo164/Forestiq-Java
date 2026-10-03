package ee.forestiq.contract;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ee.forestiq.storage.StorageProperties;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class ContractStorageService {

    private final S3Client s3Client;
    private final StorageProperties properties;

    public ContractStorageService(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    public String putPdf(UUID organizationId, UUID contractId, String fileName, byte[] content) {
        String key = "organizations/" + organizationId + "/contracts/" + contractId + "/" + fileName;
        put(key, "application/pdf", content);
        return key;
    }

    public String putSignedDocument(UUID organizationId, UUID contractId, UUID documentId,
                                    String fileName, String contentType, byte[] content) {
        String safeName = fileName == null ? "signed-document" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = "organizations/" + organizationId + "/contracts/" + contractId
            + "/signed/" + documentId + "/" + safeName;
        put(key, contentType, content);
        return key;
    }

    private void put(String key, String contentType, byte[] content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                .contentType(contentType).contentLength((long) content.length).build(),
            RequestBody.fromInputStream(new ByteArrayInputStream(content), content.length));
    }

    public byte[] get(String objectKey) {
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
        return response.asByteArray();
    }
}
