package ee.forestiq.cadastre;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ParcelMapSummary(
    String cadastralCode,
    UUID ownerId,
    String ownerName,
    String ownerStatus,
    String assignedTo,
    String phone,
    String name,
    String address,
    String postal,
    String regNr,
    String parcelType,
    Long mkDate,
    BigDecimal areaHectares,
    BigDecimal yardArea,
    BigDecimal forestArea,
    BigDecimal arableArea,
    BigDecimal meadowArea,
    BigDecimal underwaterArea,
    BigDecimal buildingsArea,
    BigDecimal otherArea,
    String county,
    String municipality,
    boolean marked,
    Double centroidLongitude,
    Double centroidLatitude,
    Double bboxWest,
    Double bboxSouth,
    Double bboxEast,
    Double bboxNorth,
    List<ParcelOwnerSummary> owners,
    UUID generationId,
    LocalDate sourceDate,
    int ownerCount,
    int subPartCount,
    int activeNotificationCount,
    int notificationCount,
    int forestFeatureCount
) {
    /**
     * Canonical active relation summary. Contact PII intentionally stays out of the
     * co-owner list; detailed owner access remains behind the owner authorization API.
     */
    public record ParcelOwnerSummary(
        UUID ownerId,
        String ownerName,
        String ownerStatus,
        String assignedTo,
        String relationSource
    ) {
        public ParcelOwnerSummary(UUID ownerId, String ownerName, String ownerStatus) {
            this(ownerId, ownerName, ownerStatus, null, null);
        }
    }
}
