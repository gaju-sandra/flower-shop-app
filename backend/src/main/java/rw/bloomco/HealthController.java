package rw.bloomco;

import java.lang.management.ManagementFactory;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.document.DocumentStore;
import rw.bloomco.messaging.EventBus;

/** Liveness / dependency check used by Docker, CI and the Postman collection. */
@RestController
public class HealthController {

    private final Db db;
    private final DocumentStore documents;
    private final EventBus events;

    public HealthController(Db db, DocumentStore documents, EventBus events) {
        this.db = db;
        this.documents = documents;
        this.events = events;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        String postgres;
        try {
            db.count("SELECT 1");
            postgres = "up";
        } catch (Exception e) {
            postgres = "down";
        }
        boolean up = "up".equals(postgres);
        return ResponseEntity.status(up ? 200 : 503).body(Json.obj(
                "status", up ? "ok" : "degraded",
                "postgres", postgres,
                "documentStore", documents.isMongoReady() ? "mongodb" : "in-memory",
                "broker", events.mode(),
                "uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000));
    }
}
