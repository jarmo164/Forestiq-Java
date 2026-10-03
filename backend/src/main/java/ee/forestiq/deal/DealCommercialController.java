package ee.forestiq.deal;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.deal.DealCommercialService.CancelDealRequest;
import ee.forestiq.deal.DealCommercialService.CommercialState;
import ee.forestiq.deal.DealCommercialService.CounterOfferRequest;
import ee.forestiq.deal.DealCommercialService.LoseDealRequest;
import ee.forestiq.deal.DealCommercialService.PrepareOfferRequest;
import ee.forestiq.deal.DealCommercialService.WinDealRequest;
import ee.forestiq.deal.DealLifecycleService.CancelDealCommand;
import ee.forestiq.deal.DealLifecycleService.CounterOfferCommand;
import ee.forestiq.deal.DealLifecycleService.LoseDealCommand;
import ee.forestiq.deal.DealLifecycleService.PrepareOfferCommand;
import ee.forestiq.deal.DealLifecycleService.WinDealCommand;

@RestController
@RequestMapping("/api/v1/deals/{dealId}/commercial")
public class DealCommercialController {

    private final DealCommercialService service;
    private final DealLifecycleService lifecycleService;

    public DealCommercialController(DealCommercialService service, DealLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public CommercialState state(@PathVariable UUID dealId) {
        return service.state(dealId);
    }

    @PostMapping("/offers")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState prepareOffer(@PathVariable UUID dealId, @Valid @RequestBody PrepareOfferRequest request) {
        return lifecycleService.prepareOffer(dealId, new PrepareOfferCommand(request.amount(), request.validUntil(), request.terms()));
    }

    @PostMapping("/offers/send")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState sendOffer(@PathVariable UUID dealId) {
        return lifecycleService.sendOffer(dealId);
    }

    @PostMapping("/counteroffers")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState counterOffer(@PathVariable UUID dealId, @Valid @RequestBody CounterOfferRequest request) {
        return lifecycleService.recordCounterOffer(dealId, new CounterOfferCommand(request.amount(), request.note()));
    }

    @PostMapping("/won")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState win(@PathVariable UUID dealId, @Valid @RequestBody WinDealRequest request) {
        return lifecycleService.win(dealId, new WinDealCommand(request.acceptedEntryId(), request.note()));
    }

    @PostMapping("/lost")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState lose(@PathVariable UUID dealId, @Valid @RequestBody LoseDealRequest request) {
        lifecycleService.lose(dealId, new LoseDealCommand(
            request.lossReason(), request.rejectedEntryId(), request.note(), null));
        return service.state(dealId);
    }

    @PostMapping("/cancelled")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public CommercialState cancel(@PathVariable UUID dealId, @Valid @RequestBody CancelDealRequest request) {
        return lifecycleService.cancel(dealId, new CancelDealCommand(request.note()));
    }
}
