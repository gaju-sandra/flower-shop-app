package rw.bloomco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Bloom & Co. flower delivery API.
 *
 * Layered architecture:
 *   HTTP layer      *Controller   - routing, request validation, RBAC (@PreAuthorize)
 *   Service layer   *Service      - business rules and transactions
 *   Data layer      JdbcTemplate (PostgreSQL), DocumentStore (MongoDB)
 *   Messaging       EventBus -> RabbitMQ topic exchange -> notification consumers
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class BloomApplication {

    public static void main(String[] args) {
        SpringApplication.run(BloomApplication.class, args);
    }
}
