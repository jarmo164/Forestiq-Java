package ee.forestiq.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.contractsource.WonDealContractSourcePort;
import ee.forestiq.deal.contractsource.WonDealContractSourcePort.WonDealContractSource;

/** Maps the deal-owned immutable WON source into contract-facing DTOs. */
@Service
public class DealContractSourceService {

    private final WonDealContractSourcePort wonDealSource;

    public DealContractSourceService(WonDealContractSourcePort wonDealSource) {
        this.wonDealSource = wonDealSource;
    }

    @Transactional(readOnly = true)
    public DealContractDraft draft(UUID dealId) {
        WonDealContractSource source = wonDealSource.requireWonAcceptedOffer(dealId);
        return new DealContractDraft(
            source.dealId(),
            source.acceptedOfferEntryId(),
            source.acceptedAmount(),
            source.acceptedTerms(),
            new SellerDraft(source.seller().name(), source.seller().code(), source.seller().address(), null),
            source.parcels().stream()
                .map(parcel -> new ParcelDraft(parcel.cadastralCode(), parcel.address(), parcel.areaHectares()))
                .toList());
    }

    @Transactional(readOnly = true)
    public DealSourceSnapshot validateSource(UUID dealId, UUID offerEntryId) {
        if (dealId == null || offerEntryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tootmislepingu loomisel peavad dealId ja offerEntryId olema määratud; sidumata käsitsi leping ei ole toetatud.");
        }
        DealContractDraft draft = draft(dealId);
        if (!offerEntryId.equals(draft.offerEntryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valitud pakkumine ei ole selle tehingu aktsepteeritud pakkumine.");
        }
        return new DealSourceSnapshot(
            draft.dealId(), draft.offerEntryId(), draft.acceptedPrice(), draft.acceptedTerms(),
            draft.seller(), List.copyOf(draft.parcels()));
    }

    public record SellerDraft(String name, String code, String address, String iban) {
    }

    public record ParcelDraft(String cadastralCode, String address, BigDecimal areaHectares) {
    }

    public record DealContractDraft(UUID dealId, UUID offerEntryId, BigDecimal acceptedPrice, String acceptedTerms,
                                    SellerDraft seller, List<ParcelDraft> parcels) {
        public DealContractDraft {
            parcels = List.copyOf(parcels);
        }
    }

    public record DealSourceSnapshot(UUID dealId, UUID offerEntryId, BigDecimal acceptedPrice, String acceptedTerms,
                                     SellerDraft seller, List<ParcelDraft> parcels) {
        public DealSourceSnapshot {
            parcels = List.copyOf(parcels);
        }
    }
}
