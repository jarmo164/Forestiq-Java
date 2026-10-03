package ee.forestiq.deal.contractsource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only, deal-owned source for a contract derived from a WON deal and its accepted offer.
 *
 * <p>Consumers receive no persistence records, timeline JSON, or HTTP DTOs. The snapshot is
 * intentionally defensive so a consumer cannot alter source data before it is audited.</p>
 */
public interface WonDealContractSourcePort {

    WonDealContractSource requireWonAcceptedOffer(UUID dealId);

    record WonDealContractSource(
        UUID dealId,
        UUID acceptedOfferEntryId,
        BigDecimal acceptedAmount,
        String acceptedTerms,
        Seller seller,
        List<Parcel> parcels) {

        public WonDealContractSource {
            Objects.requireNonNull(dealId, "dealId");
            Objects.requireNonNull(acceptedOfferEntryId, "acceptedOfferEntryId");
            Objects.requireNonNull(acceptedAmount, "acceptedAmount");
            Objects.requireNonNull(seller, "seller");
            parcels = List.copyOf(Objects.requireNonNull(parcels, "parcels"));
        }
    }

    record Seller(String name, String code, String address) {
    }

    record Parcel(String cadastralCode, String address, BigDecimal areaHectares) {
    }
}
