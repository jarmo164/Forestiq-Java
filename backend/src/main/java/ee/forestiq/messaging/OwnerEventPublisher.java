package ee.forestiq.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.forestiq.owner.OwnerService.OwnerStatusChangedEvent;

@Component
@ConditionalOnBean(RabbitTemplate.class)
public class OwnerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OwnerEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public OwnerEventPublisher(RabbitTemplate rabbitTemplate, SimpMessagingTemplate messagingTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.messagingTemplate = messagingTemplate;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOwnerStatusChanged(OwnerStatusChangedEvent event) {
        OwnerStatusEvent payload = new OwnerStatusEvent(
            event.organizationId().toString(), event.ownerId().toString(), event.status());
        try {
            rabbitTemplate.convertAndSend(MessagingConfig.OWNER_EVENT_QUEUE, payload);
        } catch (RuntimeException exception) {
            log.warn("Failed to publish owner status event to RabbitMQ for owner {}", event.ownerId(), exception);
        }
        try {
            messagingTemplate.convertAndSend("/topic/organizations/" + event.organizationId() + "/owners", payload);
        } catch (RuntimeException exception) {
            log.warn("Failed to publish owner status event to WebSocket subscribers for owner {}", event.ownerId(), exception);
        }
    }

    public record OwnerStatusEvent(String organizationId, String ownerId, String status) { }
}
