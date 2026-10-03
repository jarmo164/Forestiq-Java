package ee.forestiq.owner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class OwnerDtos {
    private OwnerDtos() { }

    // --- Owner ---
    public record OwnerListItem(
        UUID id, String displayName, String personalOrRegistryCode, String status,
        long parcelCount, Instant updatedAt, String assignedTo, String phone, Instant statusSetAt,
        List<String> dataSignals
    ) { }

    public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) { }

    public record Coordinates(double longitude, double latitude) { }

    public record LandParcel(
        UUID id, String cadastralCode, BigDecimal areaHectares, Coordinates centroid, Object boundaryGeoJson,
        String name, String county, String municipality, String parcelType, boolean marked
    ) { }

    public record Activity(UUID id, String type, String content, Instant createdAt, String createdBy) { }

    public record OwnerDetails(
        UUID id, String displayName, String personalOrRegistryCode, String status,
        long parcelCount, Instant updatedAt, String email, String phone, String assignedTo,
        String ownerType, String address, String info, Instant statusSetAt,
        Long version, List<LandParcel> parcels, List<Activity> activities, List<String> followings
    ) { }

    public record ChangeStatusRequest(
        @NotBlank @Size(max = 100) @Schema(example = "CONTACTED") String status,
        @Size(max = 2000) String comment,
        @Size(max = 200) String assignee,
        @Min(0) Long version
    ) {
        public ChangeStatusRequest(String status, String comment, String assignee) {
            this(status, comment, assignee, null);
        }
    }

    public record UpdateOwnerRequest(
        @Size(max = 300) String displayName,
        @Size(max = 100) String phone,
        @Size(max = 320) String email,
        @Size(max = 100) String ownerType,
        @Size(max = 1000) String address,
        @Size(max = 8000) String info,
        @Min(0) Long version
    ) {
        public UpdateOwnerRequest(String displayName, String phone, String email, String ownerType, String address, String info) {
            this(displayName, phone, email, ownerType, address, info, null);
        }
    }

    public record CreateOwnerRequest(
        @NotBlank @Size(max = 300) String displayName,
        @NotBlank @Size(max = 32) String personalOrRegistryCode,
        @Size(max = 64) String ownerType,
        @Size(max = 320) String email,
        @Size(max = 64) String phone
    ) { }

    // --- Cadastre labels ---
    public record CadastreLabelsResponse(List<String> setLabels, List<String> availableLabels) { }

    // --- Cadastre evaluation ---
    public record CadastreEvaluation(String ourPrice, String ownerPrice) { }
    public record SaveEvaluationRequest(@Size(max = 100) String ourPrice, @Size(max = 100) String ownerPrice) { }

    // --- Admin workdesk ---
    public record AdminSearchRequest(
        List<String> statuses, List<String> assignees, List<String> counties, List<String> municipalities,
        List<String> ownerTypes, String conservationAreas, Boolean mustHavePhone, Boolean mustNotHavePhone,
        Boolean mustHaveNoStatus, Double areaMin, Double areaMax, Double forestAreaMin, Double forestAreaMax,
        Double arableAreaMin, Double arableAreaMax, Long hasNotificationsSince, Long hasForestPlanSince,
        Long statusUpdatedSince, Long statusUpdatedTo, Boolean mustNotHaveForestPlan,
        List<String> suspended, @Positive Long maxResults,
        Boolean mustHaveNewForestNotification, Boolean mustHaveChangedForestPlan, Boolean mustHaveOwnershipTransition,
        Boolean mustHavePortfolioOwnershipAdded, Boolean mustHaveAnyDataSignal, Long dataChangedSince, Boolean mustBeUnassigned
    ) { }

    public record AdminPrepData(
        List<String> counties, List<String> municipalities, List<UserMinimal> users,
        List<String> ownerTypes, List<String> statuses
    ) { }

    public record AssignWorkRequest(
        @NotBlank @Size(max = 200) String assignee,
        @NotEmpty List<@NotNull UUID> owners,
        boolean reassign
    ) { }

    public record UserMinimal(String id, String name) { }

    // --- Owner status administration ---
    public record OwnerStatusDefinition(String id, String colorHex, int durationDays, boolean isProtected) { }
    public record SaveOwnerStatusRequest(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{6}") String colorHex,
        @Min(0) int durationDays
    ) { }

    // --- Reminders ---
    public record ReminderDto(
        Long id, String text, Instant dueTime, Instant createdAt, String creator,
        String cadastralCode, String propertyName, UUID ownerId, String ownerName
    ) { }
    public record CreateReminderRequest(
        @NotBlank @Size(max = 500) String text,
        @NotNull Instant dueTime,
        UUID ownerId, @Size(max = 100) String cadastralCode
    ) { }

    // --- Messages ---
    public record MessageDto(
        Long id, String message, String sender, String recipient, Instant createdAt, Instant noticedAt
    ) { }
    public record SendMessageRequest(@NotBlank @Size(max = 200) String recipient, @NotBlank @Size(max = 4000) String message) { }

    // --- Persons dump (contact book) ---
    public record PersonsDumpEntry(Long id, String source, String name, String phone, String address, String code) { }
    public record CreatePersonsDumpEntry(@Size(max = 100) String code, @Size(max = 300) String name,
                                         @Size(max = 100) String phone, @Size(max = 1000) String address) { }

    // --- Contract history ---
    public record ContractHistoryItem(UUID id, String sellers, String buyer, String contractNo, Instant createdAt,
                                      UUID dealId, UUID offerEntryId) { }
    public record ContractSearchFilter(String cadastre, String buyer, String seller, int offset) { }

    // --- User statistics ---
    public record StatusChangeFrame(Instant frameStart, long count) { }
    public record UserStatusChangeStats(String userId, List<StatusChangeFrame> frames) { }
}
