package ee.forestiq.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.management.SalesManagementDtos.ApplyAssignmentsRequest;
import ee.forestiq.management.SalesManagementDtos.AssignmentCandidate;
import ee.forestiq.management.SalesManagementDtos.AssignmentPreview;
import ee.forestiq.management.SalesManagementDtos.AssignmentResult;
import ee.forestiq.management.SalesManagementDtos.ForecastReport;
import ee.forestiq.management.SalesManagementDtos.FunnelCase;
import ee.forestiq.management.SalesManagementDtos.FunnelReport;
import ee.forestiq.management.SalesManagementDtos.SaveSegmentRequest;
import ee.forestiq.management.SalesManagementDtos.SegmentRunResult;
import ee.forestiq.management.SalesManagementDtos.SegmentSummary;
import ee.forestiq.management.SalesManagementDtos.StageProbability;
import ee.forestiq.management.SalesManagementDtos.UpdateStageProbabilityRequest;

@RestController("managementSalesManagementController")
@RequestMapping("/api/v1/management-analytics")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
public class SalesManagementController {
    private final SalesAnalyticsService analytics;
    private final SegmentAssignmentService segments;

    public SalesManagementController(SalesAnalyticsService analytics, SegmentAssignmentService segments) {
        this.analytics = analytics;
        this.segments = segments;
    }

    @GetMapping("/funnel")
    public FunnelReport funnel(@RequestParam(required = false) Instant from,
                               @RequestParam(required = false) Instant to,
                               @RequestParam(required = false) String salesSubject) {
        return analytics.funnel(from, to, salesSubject);
    }

    @GetMapping("/funnel/cases")
    public List<FunnelCase> funnelCases(@RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to,
                                        @RequestParam(required = false) String salesSubject) {
        return analytics.funnelCases(from, to, salesSubject);
    }

    @GetMapping("/stage-probabilities")
    public List<StageProbability> probabilities(@RequestParam(defaultValue = "false") boolean includeHistory) {
        return analytics.probabilities(includeHistory);
    }

    @PutMapping("/stage-probabilities/{stage}")
    public StageProbability updateProbability(@PathVariable String stage,
                                              @Valid @RequestBody UpdateStageProbabilityRequest request) {
        return analytics.updateProbability(stage, request);
    }

    @GetMapping("/forecast")
    public ForecastReport forecast(@RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(required = false) String salesSubject) {
        return analytics.forecast(from, to, salesSubject);
    }

    @GetMapping("/segments")
    public List<SegmentSummary> segments() {
        return segments.listSegments();
    }

    @PostMapping("/segments")
    @ResponseStatus(HttpStatus.CREATED)
    public SegmentSummary createSegment(@Valid @RequestBody SaveSegmentRequest request) {
        return segments.createSegment(request);
    }

    @PutMapping("/segments/{id}")
    public SegmentSummary updateSegment(@PathVariable UUID id, @Valid @RequestBody SaveSegmentRequest request) {
        return segments.updateSegment(id, request);
    }

    @PostMapping("/segments/{id}/run")
    public SegmentRunResult runSegment(@PathVariable UUID id) {
        return segments.runSegment(id);
    }

    @GetMapping("/assignment-candidates")
    public List<AssignmentCandidate> assignmentCandidates() {
        return segments.assignmentCandidates();
    }

    @PostMapping("/segments/{id}/assignment-preview")
    public AssignmentPreview assignmentPreview(@PathVariable UUID id) {
        return segments.assignmentPreview(id);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResult applyAssignments(@Valid @RequestBody ApplyAssignmentsRequest request) {
        return segments.applyAssignments(request);
    }
}
