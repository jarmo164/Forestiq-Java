package ee.forestiq.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import ee.forestiq.deal.DealController;
import ee.forestiq.deal.DealLifecycleService;
import ee.forestiq.deal.DealService;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.owner.OwnerController;
import ee.forestiq.owner.OwnerCursorController;
import ee.forestiq.owner.OwnerCursorPage;
import ee.forestiq.owner.OwnerCursorService;
import ee.forestiq.owner.OwnerDtos.CreateOwnerRequest;
import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.owner.OwnerDtos.OwnerDetails;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.web.ApiExceptionHandler;

@WebMvcTest(
    controllers = {OwnerController.class, OwnerCursorController.class, DealController.class},
    properties = "forestiq.cors.allowed-origins=http://localhost:3000")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class JwtControllerSecurityTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private OwnerService ownerService;
    @MockBean private OwnerCursorService ownerCursorService;
    @MockBean private DealService dealService;
    @MockBean private DealLifecycleService lifecycleService;
    @MockBean private OrganizationIdentityDirectory identityDirectory;
    @MockBean private OrganizationContext organizationContext;

    @Test
    void mapsRealmCrmManagerRoleToCreateOwnerAuthority() throws Exception {
        when(jwtDecoder.decode("manager-token")).thenReturn(jwtWithRealmRoles("manager-token", "CRM_MANAGER"));
        when(ownerService.create(any(CreateOwnerRequest.class))).thenReturn(ownerDetails());

        mockMvc.perform(post("/api/v1/owners")
                .header("Authorization", "Bearer manager-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName": "Mari Mets",
                      "personalOrRegistryCode": "48901010011",
                      "email": "mari@example.test",
                      "phone": "+37255500101"
                    }
                    """))
            .andExpect(status().isCreated());

        verify(ownerService).create(any(CreateOwnerRequest.class));
    }

    @Test
    void mapsResourceViewerRoleToReadOnlyOwnerAuthority() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithResourceRoles("viewer-token", "VIEWER"));
        when(ownerCursorService.search("", 20, null)).thenReturn(new OwnerCursorPage(List.of(), 20, false, null));

        mockMvc.perform(get("/api/v1/owners/cursor").header("Authorization", "Bearer viewer-token"))
            .andExpect(status().isOk());

        verify(ownerCursorService).search("", 20, null);
    }

    @Test
    void ignoresResourceRolesFromUnrelatedClients() throws Exception {
        when(jwtDecoder.decode("other-client-token")).thenReturn(jwt("other-client-token", Map.of(
            "resource_access", Map.of("unrelated-client", Map.of("roles", List.of("VIEWER")))
        )));

        mockMvc.perform(get("/api/v1/owners").header("Authorization", "Bearer other-client-token"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ownerService);
    }

    @Test
    void anonymousRequestCannotReachOwnerController() throws Exception {
        mockMvc.perform(get("/api/v1/owners"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(ownerService);
    }

    @Test
    void invalidRequestBodyReturnsProblemDetailValidationErrors() throws Exception {
        when(jwtDecoder.decode("manager-token")).thenReturn(jwtWithRealmRoles("manager-token", "CRM_MANAGER"));

        mockMvc.perform(post("/api/v1/owners")
                .header("Authorization", "Bearer manager-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName": "",
                      "personalOrRegistryCode": ""
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation failed"))
            .andExpect(jsonPath("$.errors[?(@.field == 'displayName')]").exists())
            .andExpect(jsonPath("$.errors[?(@.field == 'personalOrRegistryCode')]").exists());

        verifyNoInteractions(ownerService);
    }

    @Test
    void anonymousOpenApiRequestPassesSecurityFilter() throws Exception {
        mockMvc.perform(get("/api/openapi"))
            .andExpect(status().isNotFound());

        verifyNoInteractions(ownerService);
    }

    @Test
    void evaluatorCanReadEvaluationQueue() throws Exception {
        when(jwtDecoder.decode("evaluator-token")).thenReturn(jwtWithRealmRoles("evaluator-token", "EVALUATOR"));
        when(dealService.evaluationQueue()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/deals/evaluation-queue").header("Authorization", "Bearer evaluator-token"))
            .andExpect(status().isOk());

        verify(dealService).evaluationQueue();
    }

    @Test
    void viewerCannotReadEvaluationQueue() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithResourceRoles("viewer-token", "VIEWER"));

        mockMvc.perform(get("/api/v1/deals/evaluation-queue").header("Authorization", "Bearer viewer-token"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(dealService);
    }

    @Test
    void callerCanChangeOwnerStatus() throws Exception {
        when(jwtDecoder.decode("caller-token")).thenReturn(jwtWithResourceRoles("caller-token", "CALLER"));
        when(ownerService.changeStatus(eq(OWNER_ID), any(ChangeStatusRequest.class))).thenReturn(ownerDetails());

        mockMvc.perform(patch("/api/v1/owners/{ownerId}/status", OWNER_ID)
                .header("Authorization", "Bearer caller-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "CONTACTED",
                      "version": 4
                    }
                    """))
            .andExpect(status().isOk());

        verify(ownerService).changeStatus(eq(OWNER_ID), any(ChangeStatusRequest.class));
    }

    @Test
    void viewerRoleCannotCreateOwner() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithResourceRoles("viewer-token", "VIEWER"));

        mockMvc.perform(post("/api/v1/owners")
                .header("Authorization", "Bearer viewer-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName": "Mari Mets",
                      "personalOrRegistryCode": "48901010011"
                    }
                    """))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ownerService);
    }

    @Test
    void viewerRoleCannotUpdateOwner() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithResourceRoles("viewer-token", "VIEWER"));

        mockMvc.perform(put("/api/v1/owners/{ownerId}", OWNER_ID)
                .header("Authorization", "Bearer viewer-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName": "Mari Mets"
                    }
                    """))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ownerService);
    }

    @Test
    void unrelatedJwtRolesDoNotGrantControllerAccess() throws Exception {
        when(jwtDecoder.decode("other-token")).thenReturn(jwt("other-token", Map.of(
            "realm_access", Map.of("roles", List.of("offline_access", "uma_authorization"))
        )));

        mockMvc.perform(get("/api/v1/owners").header("Authorization", "Bearer other-token"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ownerService);
    }

    private static Jwt jwtWithRealmRoles(String token, String... roles) {
        return jwt(token, Map.of("realm_access", Map.of("roles", List.of(roles))));
    }

    private static Jwt jwtWithResourceRoles(String token, String... roles) {
        return jwt(token, Map.of("resource_access", Map.of("forestiq-api", Map.of("roles", List.of(roles)))));
    }

    private static Jwt jwt(String token, Map<String, Object> extraClaims) {
        var claims = new java.util.LinkedHashMap<String, Object>();
        claims.put("sub", "user-subject");
        claims.put("organization_id", ORGANIZATION_ID.toString());
        claims.putAll(extraClaims);
        return new Jwt(token, Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    private static OwnerDetails ownerDetails() {
        return new OwnerDetails(OWNER_ID, "Mari Mets", "48901010011",
            OwnerStatus.NEW, 0, Instant.now(), "mari@example.test", "+37255500101", null,
            "PERSON", null, null, null, 0L, List.of(), List.of(), List.of());
    }
}
