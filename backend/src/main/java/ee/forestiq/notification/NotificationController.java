package ee.forestiq.notification;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import ee.forestiq.notification.NotificationDtos.NotificationPreference;
import ee.forestiq.notification.NotificationDtos.UpdateNotificationPreferenceRequest;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Reminder notification preferences and delivery policy")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
public class NotificationController {

    private final NotificationPreferenceService service;

    public NotificationController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/preferences")
    @Operation(summary = "Get current user's reminder notification preferences")
    public NotificationPreference preference() {
        return service.get();
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update current user's reminder notification preferences")
    public NotificationPreference update(@Valid @RequestBody UpdateNotificationPreferenceRequest request) {
        return service.update(request);
    }
}
