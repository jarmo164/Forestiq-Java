package ee.forestiq.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.owner.OwnerDtos.MessageDto;
import ee.forestiq.owner.OwnerDtos.SendMessageRequest;

@RestController
@RequestMapping("/api/v1/messages")
@Tag(name = "Messages", description = "Internal messaging between users")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    @Operation(summary = "Send message")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public void send(@Valid @RequestBody SendMessageRequest request) {
        messageService.send(request);
    }

    @GetMapping("/received")
    @Operation(summary = "List received messages")
    @PreAuthorize("isAuthenticated()")
    public List<MessageDto> received(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return messageService.received(page, size);
    }

    @GetMapping("/sent")
    @Operation(summary = "List sent messages")
    @PreAuthorize("isAuthenticated()")
    public List<MessageDto> sent(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return messageService.sent(page, size);
    }

    @PostMapping("/mark-noticed")
    @Operation(summary = "Mark messages as noticed up to given timestamp")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNoticed(@RequestBody Map<String, Instant> body) {
        messageService.markNoticed(body.getOrDefault("upTo", Instant.now()));
    }

    @GetMapping("/unnoticed-count")
    @Operation(summary = "Count unnoticed messages")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unnoticedCount() {
        return Map.of("count", messageService.unnoticedCount());
    }

    @GetMapping("/recipients")
    @Operation(summary = "List possible message recipients")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, String>> recipients() {
        return messageService.recipients();
    }
}
