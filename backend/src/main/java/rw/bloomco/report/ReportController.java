package rw.bloomco.report;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;
import rw.bloomco.common.Text;
import rw.bloomco.document.DocumentStore;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;
    private final DocumentStore documents;
    private final EventBus events;

    public ReportController(ReportService reports, DocumentStore documents, EventBus events) {
        this.reports = reports;
        this.documents = documents;
        this.events = events;
    }

    @GetMapping("/staff-dashboard")
    @PreAuthorize("hasAuthority('dashboard:staff')")
    public Map<String, Object> staff(@AuthenticationPrincipal AuthUser user) {
        return reports.staffDashboard(user.id());
    }

    @GetMapping("/admin-dashboard")
    @PreAuthorize("hasAuthority('reports:read')")
    public Map<String, Object> admin() {
        return reports.adminDashboard();
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('reports:read')")
    public List<Map<String, Object>> sales(@RequestParam(defaultValue = "30") int days) {
        if (days < 7 || days > 365) throw ApiException.badRequest("days must be between 7 and 365");
        return reports.salesOverTime(days);
    }

    @GetMapping("/orders.csv")
    @PreAuthorize("hasAuthority('reports:read')")
    public ResponseEntity<String> csv(@RequestParam String from, @RequestParam String to) {
        Text.requireDate(from, "from");
        Text.requireDate(to, "to");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders-" + from + "-to-" + to + ".csv\"")
                .body(reports.ordersCsv(from, to));
    }

    /** Document-store views (MongoDB): audit trail + notification outbox. */
    @GetMapping("/activity")
    @PreAuthorize("hasAuthority('audit:read')")
    public Map<String, Object> activity() {
        return Json.obj(
                "audit", documents.recentAudit(60),
                "notifications", documents.recentNotifications(60),
                "infrastructure", Json.obj("documentStore", documents.isMongoReady() ? "mongodb" : "in-memory", "broker", events.mode()));
    }
}
