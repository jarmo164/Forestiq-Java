package ee.forestiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ee.forestiq.security.OrganizationContext;

class ContractControllerDownloadTest {
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private OrganizationContext organizationContext;
    private ContractStorageService storageService;
    private ContractRepository repository;
    private ContractController controller;

    @BeforeEach
    void setUp() {
        organizationContext = mock(OrganizationContext.class);
        storageService = mock(ContractStorageService.class);
        repository = mock(ContractRepository.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        controller = new ContractController(
            organizationContext,
            new ObjectMapper(),
            mock(ContractConfigurationService.class),
            mock(ContractPdfRenderer.class),
            storageService,
            repository,
            mock(DealContractSourceService.class),
            mock(ContractVersionService.class));
    }

    @Test
    void objectStorageOutageFallsBackToImmutableDatabasePdf() {
        byte[] databasePdf = "%PDF-db-copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findContractFile(ORG, CONTRACT))
            .thenReturn(Optional.of(new ContractRepository.ContractFile(databasePdf, "contracts/key.pdf", "contract.pdf")));
        when(storageService.get("contracts/key.pdf")).thenThrow(new RuntimeException("S3 unavailable"));

        assertThat(controller.download(CONTRACT).getBody()).isEqualTo(databasePdf);
    }

    @Test
    void missingObjectFallsBackToDatabasePdf() {
        byte[] databasePdf = "%PDF-db-copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findContractFile(ORG, CONTRACT))
            .thenReturn(Optional.of(new ContractRepository.ContractFile(databasePdf, "contracts/missing.pdf", "contract.pdf")));
        when(storageService.get("contracts/missing.pdf")).thenReturn(null);

        assertThat(controller.download(CONTRACT).getBody()).isEqualTo(databasePdf);
    }

    @Test
    void corruptObjectWithDifferentHashFallsBackToDatabasePdf() {
        byte[] databasePdf = "%PDF-canonical-db-copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findContractFile(ORG, CONTRACT))
            .thenReturn(Optional.of(new ContractRepository.ContractFile(databasePdf, "contracts/corrupt.pdf", "contract.pdf")));
        when(storageService.get("contracts/corrupt.pdf"))
            .thenReturn("%PDF-corrupt-object".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(controller.download(CONTRACT).getBody()).isEqualTo(databasePdf);
    }

    @Test
    void verifiedObjectCopyCanBeServed() {
        byte[] pdf = "%PDF-same-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findContractFile(ORG, CONTRACT))
            .thenReturn(Optional.of(new ContractRepository.ContractFile(pdf, "contracts/ok.pdf", "contract.pdf")));
        when(storageService.get("contracts/ok.pdf")).thenReturn(pdf.clone());

        assertThat(controller.download(CONTRACT).getBody()).isEqualTo(pdf);
    }
}
