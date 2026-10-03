package ee.forestiq.owner;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.Owner360Dtos.Coordinates;
import ee.forestiq.owner.Owner360Dtos.PortfolioMapParcel;
import ee.forestiq.security.OrganizationContext;

@Service
public class Owner360MapService {

    private final DSLContext dsl;
    private final OwnerRepository ownerRepository;
    private final OwnerAuthorizationService authorization;
    private final OrganizationContext organizationContext;

    public Owner360MapService(DSLContext dsl, OwnerRepository ownerRepository,
                              OwnerAuthorizationService authorization, OrganizationContext organizationContext) {
        this.dsl = dsl;
        this.ownerRepository = ownerRepository;
        this.authorization = authorization;
        this.organizationContext = organizationContext;
    }

    @Transactional(readOnly = true)
    public List<PortfolioMapParcel> activePortfolio(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = ownerRepository.findByIdAndOrganizationId(ownerId, orgId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Owner not found"));
        authorization.assertCanRead(owner);

        return dsl.fetch("""
            SELECT p.id AS parcel_id, p.cadastral_code, p.name, p.area_hectares,
                   ST_X(ST_Centroid(p.boundary)) AS longitude,
                   ST_Y(ST_Centroid(p.boundary)) AS latitude
              FROM owner_parcel_relation opr
              JOIN land_parcel p
                ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
             WHERE opr.organization_id = ? AND opr.owner_id = ? AND opr.active = TRUE
             ORDER BY p.cadastral_code, p.id
            """, orgId, ownerId).map(row -> {
                Double longitude = row.get("longitude", Double.class);
                Double latitude = row.get("latitude", Double.class);
                Coordinates centroid = longitude == null || latitude == null
                    ? null : new Coordinates(longitude, latitude);
                return new PortfolioMapParcel(
                    row.get("parcel_id", UUID.class),
                    row.get("cadastral_code", String.class),
                    row.get("name", String.class),
                    row.get("area_hectares", BigDecimal.class),
                    centroid);
            });
    }
}