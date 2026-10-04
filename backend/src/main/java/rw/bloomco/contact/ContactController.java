package rw.bloomco.contact;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;
import rw.bloomco.common.RateLimiter;
import rw.bloomco.common.Text;
import rw.bloomco.common.Validation;
import rw.bloomco.document.DocumentStore;
import rw.bloomco.messaging.EventBus;
import rw.bloomco.security.AuthUser;

/** Contact messages are documents (MongoDB): free-form, with an embedded reply thread. */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    public record ContactBody(
            @NotBlank(message = "Please tell us your name") @Size(min = 2, max = 80, message = "Please tell us your name") String name,
            @NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) String email,
            @Pattern(regexp = "^$|" + Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @NotBlank(message = "Subject is too short") @Size(min = 3, max = 120, message = "Subject is too short") String subject,
            @NotBlank(message = "Message should be at least 10 characters") @Size(min = 10, max = 2000, message = "Message should be at least 10 characters") String message) {}

    public record StatusBody(@NotBlank @Pattern(regexp = "new|read|replied|closed") String status) {}

    public record ReplyBody(@NotBlank @Size(min = 2, max = 2000) String body) {}

    private final DocumentStore documents;
    private final EventBus events;
    private final RateLimiter limiter;

    public ContactController(DocumentStore documents, EventBus events, RateLimiter limiter) {
        this.documents = documents;
        this.events = events;
        this.limiter = limiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> send(@Valid @RequestBody ContactBody body, @AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        limiter.check(req, "contact", 10, Duration.ofHours(1));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", body.name().trim());
        data.put("email", body.email().trim().toLowerCase());
        data.put("phone", Text.blankToNull(Text.cleanPhone(body.phone())));
        data.put("subject", body.subject().trim());
        data.put("message", body.message().trim());
        data.put("userId", user == null ? null : user.id());
        var doc = documents.createContact(data);
        events.publish("contact.received", Json.obj("actorId", user == null ? null : user.id(), "entity", "contact_message",
                "entityId", String.valueOf(doc.get("_id")), "name", doc.get("name"), "email", doc.get("email"), "subject", doc.get("subject")));
        return Json.obj("message", "Thank you! Your message has been sent. 💌");
    }

    @GetMapping
    @PreAuthorize("hasAuthority('messages:read')")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return documents.listContacts(Text.oneOf(status, Set.of("new", "read", "replied", "closed"), "status"));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('messages:read')")
    public Map<String, Object> setStatus(@PathVariable String id, @Valid @RequestBody StatusBody body) {
        var doc = documents.updateContact(id, body.status(), null);
        if (doc == null) throw ApiException.notFound("Message not found");
        return doc;
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('messages:reply')")
    public Map<String, Object> reply(@PathVariable String id, @Valid @RequestBody ReplyBody body, @AuthenticationPrincipal AuthUser user) {
        var doc = documents.updateContact(id, "replied", Json.obj("body", body.body().trim(), "authorId", user.id(), "authorName", user.name()));
        if (doc == null) throw ApiException.notFound("Message not found");
        events.publish("contact.replied", Json.obj("actorId", user.id(), "entity", "contact_message", "entityId", id,
                "email", doc.get("email"), "name", doc.get("name"), "subject", doc.get("subject"), "reply", body.body().trim(),
                "staffName", user.name()));
        return doc;
    }
}
