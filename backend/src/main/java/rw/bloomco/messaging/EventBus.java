package rw.bloomco.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events to RabbitMQ (topic exchange "flowershop.events").
 *
 *  API --publish(routingKey)--> [flowershop.events] --> notifications.email --> email consumer
 *                                                   +-> notifications.sms   --> SMS consumer
 *                                                   +-> audit.events        --> audit log (MongoDB)
 *
 * When RabbitMQ is not configured or not reachable, the same routing rules are
 * applied by an in-process bus, so features keep working in local development.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    public static final String EXCHANGE = "flowershop.events";
    public static final String DLX = EXCHANGE + ".dlx";
    public static final String DEAD_LETTER_QUEUE = "events.dead-letter";

    /** queue name -> routing keys bound to it */
    public static final Map<String, List<String>> QUEUES = new LinkedHashMap<>();

    static {
        QUEUES.put(Queues.EMAIL, List.of("user.registered", "auth.password_reset_requested", "order.placed",
                "order.status_changed", "payment.completed", "contact.received", "contact.replied", "staff.created"));
        QUEUES.put(Queues.SMS, List.of("order.placed", "order.status_changed", "delivery.assigned"));
        QUEUES.put(Queues.AUDIT, List.of("#"));
    }

    public static final class Queues {
        public static final String EMAIL = "notifications.email";
        public static final String SMS = "notifications.sms";
        public static final String AUDIT = "audit.events";

        private Queues() {}
    }

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final NotificationHandlers handlers;
    private final Executor executor;
    private volatile boolean rabbitUp;

    public EventBus(ObjectProvider<RabbitTemplate> rabbit, ObjectMapper mapper, NotificationHandlers handlers,
            @Qualifier("applicationTaskExecutor") Executor executor) {
        this.rabbit = rabbit.getIfAvailable();
        this.mapper = mapper;
        this.handlers = handlers;
        this.executor = executor;
    }

    void setRabbitUp(boolean up) {
        this.rabbitUp = up;
    }

    public String mode() {
        return rabbitUp ? "rabbitmq" : "in-process";
    }

    /** Publish a domain event. Never throws: messaging must not break a checkout. */
    public Map<String, Object> publish(String routingKey, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("event", routingKey);
        message.put("occurredAt", Instant.now().toString());
        message.put("data", data);
        if (rabbitUp && rabbit != null) {
            try {
                rabbit.convertAndSend(EXCHANGE, routingKey, mapper.writeValueAsBytes(message), m -> {
                    MessageProperties p = m.getMessageProperties();
                    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    p.setMessageId((String) message.get("id"));
                    p.setType(routingKey);
                    return m;
                });
                return message;
            } catch (Exception e) {
                log.warn("RabbitMQ publish failed ({}), using local bus", e.getMessage());
            }
        }
        dispatchLocally(routingKey, message);
        return message;
    }

    private void dispatchLocally(String routingKey, Map<String, Object> message) {
        QUEUES.forEach((queue, bindings) -> {
            if (matches(bindings, routingKey)) {
                executor.execute(() -> {
                    try {
                        handlers.handle(queue, message);
                    } catch (Exception e) {
                        log.error("[{}] handler failed: {}", queue, e.getMessage());
                    }
                });
            }
        });
    }

    static boolean matches(List<String> bindings, String key) {
        return bindings.stream().anyMatch(b -> b.equals("#") || b.equals(key)
                || (b.endsWith(".*") && key.startsWith(b.substring(0, b.length() - 1))));
    }
}
