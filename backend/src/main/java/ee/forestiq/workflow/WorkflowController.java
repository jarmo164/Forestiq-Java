package ee.forestiq.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import ee.forestiq.workflow.WorkflowDtos.AssigneeOption;
import ee.forestiq.workflow.WorkflowDtos.CompleteActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.ContactRequest;
import ee.forestiq.workflow.WorkflowDtos.ContactResult;
import ee.forestiq.workflow.WorkflowDtos.DelegateActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.HistoryPage;
import ee.forestiq.workflow.WorkflowDtos.OpenActivityOption;
import ee.forestiq.workflow.WorkflowDtos.OutcomeDefinition;
import ee.forestiq.workflow.WorkflowDtos.PostponeActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.UpdateOutcomeRequest;
import ee.forestiq.workflow.WorkflowDtos.WorkQueueResponse;

@RestController
@RequestMapping("/api/v1/workflow")
@Tag(name = "Contact workflow", description = "Structured contacts, auditable follow-up activities and personal work queue")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/outcomes")
    @Operation(summary = "List organization contact outcome definitions")
    public List<OutcomeDefinition> outcomes() {
        return service.listOutcomes();
    }

    @PutMapping("/outcomes/{code}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @Operation(summary = "Create or update an organization contact outcome")
    public OutcomeDefinition updateOutcome(
        @PathVariable String code,
        @Valid @RequestBody UpdateOutcomeRequest request) {
        return service.updateOutcome(code, request);
    }

    @GetMapping("/assignees")
    @Operation(summary = "List active organization users eligible for activity assignment")
    public List<AssigneeOption> assignees() {
        return service.listAssignees();
    }

    @GetMapping("/owners/{ownerId}/open-activities")
    @Operation(summary = "List open owner activities that a new contact may link to")
    public List<OpenActivityOption> openActivities(@PathVariable UUID ownerId) {
        return service.openActivities(ownerId);
    }

    @PostMapping("/owners/{ownerId}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a structured contact and optionally link or create the next activity")
    public ContactResult createContact(
        @PathVariable UUID ownerId,
        @Valid @RequestBody ContactRequest request) {
        return service.createContact(ownerId, request);
    }

    @GetMapping("/owners/{ownerId}/history")
    @Operation(summary = "Read a filtered, stable-paginated owner decision history")
    public HistoryPage ownerHistory(
        @PathVariable UUID ownerId,
        @RequestParam(required = false) String activityType,
        @RequestParam(required = false) String outcome,
        @RequestParam(required = false) String author,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) Instant cursorCreatedAt,
        @RequestParam(required = false) UUID cursorId,
        @RequestParam(defaultValue = "50") int limit) {
        return service.ownerHistory(ownerId, activityType, outcome, author, from, to, cursorCreatedAt, cursorId, limit);
    }

    @GetMapping("/deals/{dealId}/history")
    @Operation(summary = "Read a filtered, stable-paginated deal decision history")
    public HistoryPage dealHistory(
        @PathVariable UUID dealId,
        @RequestParam(required = false) String activityType,
        @RequestParam(required = false) String outcome,
        @RequestParam(required = false) String author,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) Instant cursorCreatedAt,
        @RequestParam(required = false) UUID cursorId,
        @RequestParam(defaultValue = "50") int limit) {
        return service.dealHistory(dealId, activityType, outcome, author, from, to, cursorCreatedAt, cursorId, limit);
    }

    @GetMapping("/work-queue")
    @Operation(summary = "Read today's, week's or overdue active activities for the current user")
    public WorkQueueResponse workQueue(@RequestParam(defaultValue = "TODAY") String view) {
        return service.workQueue(view);
    }

    @PostMapping("/activities/{activityId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Complete an open activity and retain it in audit history")
    public void complete(
        @PathVariable long activityId,
        @Valid @RequestBody CompleteActivityRequest request) {
        service.completeActivity(activityId, request);
    }

    @PostMapping("/activities/{activityId}/postpone")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Postpone an open activity while retaining the old due time")
    public void postpone(
        @PathVariable long activityId,
        @Valid @RequestBody PostponeActivityRequest request) {
        service.postponeActivity(activityId, request);
    }

    @PostMapping("/activities/{activityId}/delegate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delegate an open activity to an active authorized organization user")
    public void delegate(
        @PathVariable long activityId,
        @Valid @RequestBody DelegateActivityRequest request) {
        service.delegateActivity(activityId, request);
    }
}
