package rw.bloomco.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import rw.bloomco.config.AppProperties;

/**
 * RabbitMQ topology + consumers. Only active when RABBITMQ_URL is set.
 *   exchange  flowershop.events (topic, durable)
 *   queues    notifications.email, notifications.sms, audit.events (durable, dead-lettered)
 *   DLX       flowershop.events.dlx (fanout) -> events.dead-letter
 */
@Configuration
@ConditionalOnExpression("!'${app.rabbit.url:}'.isEmpty()")
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);
    private final java.util.List<SimpleMessageListenerContainer> containers = new java.util.concurrent.CopyOnWriteArrayList<>();

    @jakarta.annotation.PreDestroy
    void stopConsumers() {
        containers.forEach(SimpleMessageListenerContainer::stop);
    }

    @Bean
    public CachingConnectionFactory rabbitConnectionFactory(AppProperties props) throws Exception {
        var factory = new CachingConnectionFactory(new URI(props.rabbit().url()));
        factory.getRabbitConnectionFactory().setConnectionTimeout(4000);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory cf) {
        return new RabbitTemplate(cf);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(CachingConnectionFactory cf) {
        var admin = new RabbitAdmin(cf);
        admin.setAutoStartup(false); // declared explicitly once the broker is reachable
        return admin;
    }

    /** Declares the topology and starts consumers once the app is up; falls back if the broker is down. */
    @EventListener(ApplicationReadyEvent.class)
    public void start(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        CachingConnectionFactory cf = ctx.getBean(CachingConnectionFactory.class);
        RabbitAdmin admin = ctx.getBean(RabbitAdmin.class);
        EventBus bus = ctx.getBean(EventBus.class);
        NotificationHandlers handlers = ctx.getBean(NotificationHandlers.class);
        ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
        try {
            cf.createConnection().close();
            var exchange = new TopicExchange(EventBus.EXCHANGE, true, false);
            var dlx = new FanoutExchange(EventBus.DLX, true, false);
            var deadLetters = QueueBuilder.durable(EventBus.DEAD_LETTER_QUEUE).build();
            admin.declareExchange(exchange);
            admin.declareExchange(dlx);
            admin.declareQueue(deadLetters);
            admin.declareBinding(BindingBuilder.bind(deadLetters).to(dlx));
            for (var entry : EventBus.QUEUES.entrySet()) {
                Queue q = QueueBuilder.durable(entry.getKey()).deadLetterExchange(EventBus.DLX).build();
                admin.declareQueue(q);
                for (String key : entry.getValue()) {
                    Binding b = BindingBuilder.bind(q).to(exchange).with(key);
                    admin.declareBinding(b);
                }
                startConsumer(cf, entry.getKey(), handlers, mapper);
            }
            bus.setRabbitUp(true);
            log.info("RabbitMQ connected - exchange '{}' with {} queues", EventBus.EXCHANGE, EventBus.QUEUES.size());
        } catch (Exception e) {
            log.warn("RabbitMQ unavailable ({}) - using in-process event bus", e.getMessage());
        }
    }

    private void startConsumer(CachingConnectionFactory cf, String queue, NotificationHandlers handlers, ObjectMapper mapper) {
        var container = new SimpleMessageListenerContainer(cf);
        container.setQueueNames(queue);
        container.setPrefetchCount(10);
        container.setDefaultRequeueRejected(false); // failures go to the dead-letter queue
        container.setMessageListener(message -> {
            try {
                Map<String, Object> event = mapper.readValue(message.getBody(), new TypeReference<>() {});
                handlers.handle(queue, event);
            } catch (Exception e) {
                log.error("[{}] handler failed: {}", queue, e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e);
            }
        });
        container.afterPropertiesSet();
        container.start();
        containers.add(container);
    }
}
