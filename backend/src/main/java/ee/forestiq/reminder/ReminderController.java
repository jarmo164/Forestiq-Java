package ee.forestiq.reminder;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.owner.OwnerDtos.CreateReminderRequest;
import ee.forestiq.owner.OwnerDtos.ReminderDto;

@RestController
@RequestMapping("/api/v1/reminders")
@Tag(name = "Reminders", description = "Personal reminders linked to owners and cadastres")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    @Operation(summary = "List my reminders")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public List<ReminderDto> list() {
        return reminderService.getMyReminders();
    }

    @GetMapping("/dashboard")
    @Operation(summary = "List all organization reminders for the management dashboard")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<ReminderDto> dashboard() {
        return reminderService.getOrganizationReminders();
    }

    @PostMapping
    @Operation(summary = "Create reminder")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@Valid @RequestBody CreateReminderRequest request) {
        reminderService.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update an open reminder created by the current user and retain an audit event")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable Long id, @Valid @RequestBody UpdateReminderRequest request) {
        reminderService.update(id, request.text(), request.dueTime());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete reminder")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        reminderService.delete(id);
    }

    public record UpdateReminderRequest(@NotBlank @Size(max = 500) String text, @NotNull Instant dueTime) { }
}
