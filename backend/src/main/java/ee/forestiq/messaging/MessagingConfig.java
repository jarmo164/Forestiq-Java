package ee.forestiq.messaging;

import java.security.Principal;
import java.util.List;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class MessagingConfig implements WebSocketMessageBrokerConfigurer {

    public static final String OWNER_EVENT_QUEUE = "forestiq.owner-events";

    private final String[] allowedOrigins;

    public MessagingConfig(@Value("${forestiq.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = List.of(allowedOrigins.split(",")).stream()
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
    }

    @Bean
    Queue ownerEventQueue() {
        return new Queue(OWNER_EVENT_QUEUE, true);
    }

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new OrganizationTopicAuthorizationInterceptor());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(allowedOrigins);
    }

    private static final class OrganizationTopicAuthorizationInterceptor implements ChannelInterceptor {
        private static final String ORGANIZATION_TOPIC_PREFIX = "/topic/organizations/";

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor == null || accessor.getMessageType() != SimpMessageType.SUBSCRIBE) {
                return message;
            }
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(ORGANIZATION_TOPIC_PREFIX)) {
                return message;
            }
            String requestedOrgId = destination.substring(ORGANIZATION_TOPIC_PREFIX.length()).split("/", 2)[0];
            if (!requestedOrgId.equals(authenticatedOrganizationId(accessor.getUser()))) {
                throw new AccessDeniedException("Cannot subscribe to another organization's topic");
            }
            return message;
        }

        private String authenticatedOrganizationId(Principal principal) {
            if (principal instanceof Authentication authentication && authentication.getPrincipal() instanceof Jwt jwt) {
                return jwt.getClaimAsString("organization_id");
            }
            return null;
        }
    }
}
