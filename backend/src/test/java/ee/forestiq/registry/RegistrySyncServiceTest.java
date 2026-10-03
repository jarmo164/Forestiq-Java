package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import ee.forestiq.registry.ExternalRegistryService.ForestRecord;
import ee.forestiq.registry.ExternalRegistryService.ForestRegistryResponse;
import ee.forestiq.registry.RegistryDataRepository.ParcelOwnerRef;
import ee.forestiq.security.OrganizationContext;

class RegistrySyncServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CO_OWNER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CODE = "10001:001:0001";

    private ExternalRegistryService external;
    private RegistryDataRepository dataRepository;
    private RegistrySyncAuditRepository auditRepository;
    private RegistrySyncService service;

    @BeforeEach
    void setUp() {
        external = mock(ExternalRegistryService.class);
        dataRepository = mock(RegistryDataRepository.class);
        auditRepository = mock(RegistrySyncAuditRepository.class);
        OrganizationContext context = mock(OrganizationContext.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(context.requiredOrganizationId()).thenReturn(ORG_ID);
        when(context.subject()).thenReturn("manager-subject");
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);

        service = new RegistrySyncService(external, dataRepository, context, transactionManager, auditRepository);
    }

    @Test
    void maaametParcelActionIsDiagnosticAndDoesNotOverwriteCanonicalCadastreData() {
        ObjectMapper json = new ObjectMapper();
        var response = json.createObjectNode().put("type", "FeatureCollection");
        var cadastre = new ExternalRegistryService.MaaametCadastre(
            CODE,
            "New address",
            "Harju maakond",
            "Tallinn",
            "123456",
            "private",
            BigDecimal.TEN,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "{\"type\":\"Polygon\",\"coordinates\":[]}",
            response);
        when(external.fetchMaaametCadastre(CODE)).thenReturn(Optional.of(cadastre));

        var result = service.refreshMaaamet(CODE);

        assertThat(result.registry()).isEqualTo("MAAAMET_WFS_DIAGNOSTIC");
        assertThat(result.recordsUpdated()).isZero();
        assertThat(result.message()).contains("kanoniseeritud katastriandmeid ei muudetud");
        verify(dataRepository).ensureParcelExists(ORG_ID, CODE);
        verify(dataRepository, never()).updateMaaametAttributes(any(), any(), any());
        verify(dataRepository, never()).updateBoundary(any(), any(), any());
        verify(dataRepository, never()).clearBoundary(any(), any());
        verify(auditRepository).logSync(eq(ORG_ID), eq(CODE), eq("MAAAMET_WFS_DIAGNOSTIC"),
            eq("SUCCESS"), eq(0), eq(null), eq(response), eq(null), eq("manager-subject"));
    }

    @Test
    void notificationRefreshCreatesSignalForEveryActiveCoOwner() {
        ObjectMapper json = new ObjectMapper();
        ForestRecord record = new ForestRecord(42L, "TEATIS-42", "UU", "Kehtiv",
            BigDecimal.valueOf(2.5), null, json.createObjectNode().put("sys_id", 42));
        ForestRegistryResponse response = new ForestRegistryResponse("Metsateatiste", "https://example.test/wfs",
            json.createObjectNode(), List.of(record));
        when(external.fetchForestNotifications(CODE)).thenReturn(response);
        when(dataRepository.parcelOwnerRef(ORG_ID, CODE)).thenReturn(new ParcelOwnerRef(PARCEL_ID, List.of(OWNER_ID, CO_OWNER_ID)));
        when(dataRepository.activeNotificationKeys(ORG_ID, CODE)).thenReturn(Set.of());
        when(dataRepository.replaceForestNotifications(ORG_ID, CODE, response.records())).thenReturn(1);

        var result = service.refreshForestNotifications(CODE);

        assertThat(result.success()).isTrue();
        assertThat(result.recordsUpdated()).isEqualTo(1);
        verify(dataRepository).ensureParcelExists(ORG_ID, CODE);
        verify(dataRepository).replaceForestNotifications(ORG_ID, CODE, response.records());
        verify(auditRepository).recordDataSignal(eq(ORG_ID), eq(OWNER_ID), eq(PARCEL_ID),
            eq("NEW_FOREST_NOTIFICATION"), eq("METSAREGISTER_TEATISED"), eq(CODE + ":42"),
            any(String.class), eq(record.raw()), eq("manager-subject"));
        verify(auditRepository).recordDataSignal(eq(ORG_ID), eq(CO_OWNER_ID), eq(PARCEL_ID),
            eq("NEW_FOREST_NOTIFICATION"), eq("METSAREGISTER_TEATISED"), eq(CODE + ":42"),
            any(String.class), eq(record.raw()), eq("manager-subject"));
        verify(auditRepository).logSync(eq(ORG_ID), eq(CODE), eq("METSAREGISTER_TEATISED"),
            eq("SUCCESS"), eq(1), eq(null), eq(response.rawResponse()), eq(response.requestUrl()), eq("manager-subject"));
    }

    @Test
    void existingNotificationIsReplacedWithoutDuplicateChangeSignal() {
        ObjectMapper json = new ObjectMapper();
        ForestRecord record = new ForestRecord(42L, "TEATIS-42", null, null, null, null,
            json.createObjectNode().put("sys_id", 42));
        ForestRegistryResponse response = new ForestRegistryResponse("Metsateatiste", "https://example.test/wfs",
            json.createObjectNode(), List.of(record));
        when(external.fetchForestNotifications(CODE)).thenReturn(response);
        when(dataRepository.parcelOwnerRef(ORG_ID, CODE)).thenReturn(new ParcelOwnerRef(PARCEL_ID, List.of(OWNER_ID)));
        when(dataRepository.activeNotificationKeys(ORG_ID, CODE)).thenReturn(Set.of("42"));
        when(dataRepository.replaceForestNotifications(ORG_ID, CODE, response.records())).thenReturn(1);

        var result = service.refreshForestNotifications(CODE);

        assertThat(result.success()).isTrue();
        verify(auditRepository, never()).recordDataSignal(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditRepository).logSync(eq(ORG_ID), eq(CODE), eq("METSAREGISTER_TEATISED"),
            eq("SUCCESS"), eq(1), eq(null), eq(response.rawResponse()), eq(response.requestUrl()), eq("manager-subject"));
    }
}
