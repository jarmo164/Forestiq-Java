package ee.forestiq.cadastre;

import java.util.List;

public final class CadastreDtos {

    private CadastreDtos() {
    }

    public record CadastreNotificationDto(
        Long id,
        String cadastralCode,
        String notificationNumber,
        Double amountToBeCut,
        Double area,
        Long cadastreSubpartCode,
        String damageCode,
        Long confirmationDate,
        Long registrationDate,
        String decision,
        String state,
        String workCode,
        boolean archived,
        Long archiveDate
    ) {}

    public record CadastreNotificationPage(
        List<CadastreNotificationDto> items,
        Long nextCursor,
        boolean hasMore
    ) {}

    /** Stable two-column keyset cursor for time-ordered archive browsing. */
    public record CadastreNotificationArchivePage(
        List<CadastreNotificationDto> items,
        Long nextArchiveDate,
        Long nextId,
        boolean hasMore
    ) {}

    public record ForestFeatureDto(
        Long id,
        String cadastralCode,
        String sourceLayer,
        String sourceId,
        Integer subpartCode,
        String title,
        String workCode,
        String decision,
        Double area,
        Double volume,
        Long eventDate,
        String attributes,
        String geometry
    ) {}

    public record ForestFeaturePage(
        List<ForestFeatureDto> items,
        Long nextCursor,
        boolean hasMore
    ) {}

    public record ForestFeatureRawDto(
        Long id,
        String attributes,
        String geometry
    ) {}

    public record SubPartDto(Long id, String cadastralCode, Integer subPartCode,
        String treeTypeCode, Double area, String polygon) {}

    public record SubPartPage(
        List<SubPartDto> items,
        Long nextCursor,
        boolean hasMore
    ) {}

    public record SubPartGeometryDto(
        Long id,
        String cadastralCode,
        Integer subPartCode,
        String polygon
    ) {}
}
