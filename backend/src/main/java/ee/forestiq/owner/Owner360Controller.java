package ee.forestiq.owner;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import ee.forestiq.owner.Owner360Dtos.Owner360Overview;
import ee.forestiq.owner.Owner360Dtos.ParcelRelationsPage;
import ee.forestiq.owner.Owner360Dtos.PortfolioMapParcel;
import ee.forestiq.owner.Owner360Dtos.TimelinePage;
import ee.forestiq.owner.Owner360Dtos.WorkflowSummary;

@RestController
@RequestMapping("/api/v1/owners/{ownerId}/360")
@Tag(name = "Owner 360", description = "Progressively loaded owner truth and decision workspace")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
public class Owner360Controller {

    private final Owner360Service service;
    private final Owner360MapService mapService;

    public Owner360Controller(Owner360Service service, Owner360MapService mapService) {
        this.service = service;
        this.mapService = mapService;
    }

    @GetMapping
    @Operation(summary = "Get the fast permission-aware owner 360 overview")
    public Owner360Overview overview(@PathVariable UUID ownerId) {
        return service.overview(ownerId);
    }

    @GetMapping("/relations")
    @Operation(summary = "List active and optionally historical owner-to-parcel relations")
    public ParcelRelationsPage relations(
            @PathVariable UUID ownerId,
            @RequestParam(defaultValue = "false") boolean includeHistorical,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return service.relations(ownerId, includeHistorical, offset, limit);
    }

    @GetMapping("/map")
    @Operation(summary = "List the complete lightweight active parcel projection for the owner map")
    public List<PortfolioMapParcel> map(@PathVariable UUID ownerId) {
        return mapService.activePortfolio(ownerId);
    }

    @GetMapping("/history")
    @Operation(summary = "Get a normalized chronological owner timeline")
    public TimelinePage history(
            @PathVariable UUID ownerId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return service.history(ownerId, offset, limit);
    }

    @GetMapping("/workflow")
    @Operation(summary = "Get the current ForestIQ CRM and workflow context for an owner")
    public WorkflowSummary workflow(@PathVariable UUID ownerId) {
        return service.workflow(ownerId);
    }
}