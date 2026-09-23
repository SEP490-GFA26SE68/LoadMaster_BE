package fu.se184491.loadmaster_be.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration (S3-10).
 *
 * <p>Endpoint:  {@code /ws} (SockJS fallback enabled)
 * <p>Topics:    {@code /topic/jobs/{jobId}} — pushed by server when job status changes
 * <p>App prefix:{@code /app} — client-to-server messages (not used yet)
 *
 * <p>React client usage:
 * <pre>
 *   const client = new Client({ brokerURL: 'ws://host/ws' });
 *   client.subscribe('/topic/jobs/abc-uuid', msg => { ... });
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** STOMP endpoint the client connects to. */
    public static final String STOMP_ENDPOINT = "/ws";

    /** Destination prefix for server-to-client broadcasts. */
    public static final String TOPIC_PREFIX = "/topic";

    /** Destination prefix for client-to-server app messages. */
    public static final String APP_PREFIX = "/app";

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple in-memory broker for /topic destinations
        registry.enableSimpleBroker(TOPIC_PREFIX);
        // Client-to-server messages must be prefixed with /app
        registry.setApplicationDestinationPrefixes(APP_PREFIX);
    }
}
