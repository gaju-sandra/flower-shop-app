package rw.bloomco.messaging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import rw.bloomco.common.Text;
import rw.bloomco.config.AppProperties;
import rw.bloomco.document.DocumentStore;

/**
 * Consumers for the three queues: builds the email / SMS for each domain event
 * and writes every event to the audit trail. Called by RabbitMQ listeners, or by
 * the in-process bus when RabbitMQ is not available.
 */
@Component
public class NotificationHandlers {

    private static final Map<String, String> STATUS_TEXT = Map.of(
            "confirmed", "has been confirmed ✅",
            "preparing", "is being prepared by our florists 💐",
            "ready", "is ready for delivery 📦",
            "out_for_delivery", "is out for delivery 🚚",
            "delivered", "has been delivered. Enjoy your flowers! 🌸",
            "cancelled", "has been cancelled");

    private final Notifiers notifiers;
    private final DocumentStore documents;
    private final String clientUrl;

    public NotificationHandlers(Notifiers notifiers, DocumentStore documents, AppProperties props) {
        this.notifiers = notifiers;
        this.documents = documents;
        this.clientUrl = props.clientUrl();
    }

    @SuppressWarnings("unchecked")
    public void handle(String queue, Map<String, Object> message) {
        String event = (String) message.get("event");
        Map<String, Object> data = (Map<String, Object>) message.getOrDefault("data", Map.of());
        switch (queue) {
            case EventBus.Queues.EMAIL -> email(event, data);
            case EventBus.Queues.SMS -> sms(event, data);
            case EventBus.Queues.AUDIT -> audit(event, data);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ email

    private void email(String event, Map<String, Object> d) {
        String to = s(d, "email");
        String subject;
        String html;
        switch (event) {
            case "user.registered" -> {
                subject = "Welcome to Bloom & Co. 🌸";
                html = "<p>Hi " + s(d, "firstName") + ",</p><p>Your account is ready. Fresh flowers for every moment are just a click away.</p>"
                        + "<p><a href=\"" + clientUrl + "/products\">Browse our collection →</a></p>";
            }
            case "staff.created" -> {
                subject = "Your Bloom & Co. staff account";
                html = "<p>Hi " + s(d, "firstName") + ",</p><p>An administrator created a staff account for you ("
                        + s(d, "staffRole") + "). Sign in at <a href=\"" + clientUrl + "/login\">" + clientUrl
                        + "/login</a> with the password you were given, then change it from your profile.</p>";
            }
            case "auth.password_reset_requested" -> {
                subject = "Reset your password";
                html = "<p>Hi " + s(d, "firstName") + ",</p><p>Click the link below to choose a new password. It expires in 30 minutes.</p>"
                        + "<p><a href=\"" + s(d, "resetUrl") + "\">Reset my password</a></p><p>If you did not request this, you can ignore this email.</p>";
            }
            case "order.placed" -> {
                subject = "Order " + s(d, "orderNumber") + " received 🌷";
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) d.getOrDefault("items", List.of());
                String lines = items.stream().map(i -> "<li>" + i.get("quantity") + " × " + i.get("name") + "</li>")
                        .collect(Collectors.joining());
                html = "<p>Hi " + s(d, "customerName") + ",</p><p>Thank you for your order! Here is a summary:</p><ul>" + lines
                        + "</ul><p><b>Total:</b> " + Text.money((Number) d.get("total")) + "<br/><b>Delivery:</b> "
                        + s(d, "deliveryDate") + " (" + s(d, "deliveryTime") + ")<br/><b>Address:</b> " + s(d, "address") + "</p>"
                        + "<p><a href=\"" + clientUrl + "/account/orders/" + s(d, "orderId") + "\">Track your order →</a></p>";
            }
            case "order.status_changed" -> {
                String text = STATUS_TEXT.get(s(d, "status"));
                if (text == null) return;
                subject = "Order " + s(d, "orderNumber") + " update";
                html = "<p>Hi " + s(d, "customerName") + ",</p><p>Your order <b>" + s(d, "orderNumber") + "</b> " + text + "</p>"
                        + "<p><a href=\"" + clientUrl + "/account/orders/" + s(d, "orderId") + "\">View order →</a></p>";
            }
            case "payment.completed" -> {
                subject = "Payment received for " + s(d, "orderNumber");
                html = "<p>We received your payment of <b>" + Text.money((Number) d.get("amount")) + "</b> via " + s(d, "method")
                        + ".<br/>Reference: " + s(d, "reference") + "</p>";
            }
            case "contact.received" -> {
                subject = "We received your message 💌";
                html = "<p>Hi " + s(d, "name") + ",</p><p>Thanks for reaching out about “" + s(d, "subject")
                        + "”. Our team will reply within one business day.</p>";
            }
            case "contact.replied" -> {
                subject = "Re: " + s(d, "subject");
                html = "<p>Hi " + s(d, "name") + ",</p><p>" + escape(s(d, "reply")).replace("\n", "<br/>") + "</p><p>— "
                        + s(d, "staffName") + ", Bloom &amp; Co.</p>";
            }
            default -> {
                return;
            }
        }
        notifiers.sendEmail(to, subject, html, event, d.get("userId"));
    }

    // ------------------------------------------------------------------ sms

    private void sms(String event, Map<String, Object> d) {
        String body = null;
        String to = s(d, "phone");
        if ("order.placed".equals(event)) {
            body = "Bloom&Co: Order " + s(d, "orderNumber") + " received. Total " + Text.money((Number) d.get("total"))
                    + ". Delivery " + s(d, "deliveryDate") + ".";
        } else if ("order.status_changed".equals(event) && STATUS_TEXT.containsKey(s(d, "status"))) {
            body = "Bloom&Co: Your order " + s(d, "orderNumber") + " "
                    + STATUS_TEXT.get(s(d, "status")).replaceAll("[^\\x20-\\x7E]", "").trim() + ".";
        } else if ("delivery.assigned".equals(event) && d.get("staffPhone") != null) {
            to = s(d, "staffPhone");
            body = "Bloom&Co: Delivery " + s(d, "orderNumber") + " assigned to you for " + s(d, "deliveryDate") + " ("
                    + s(d, "deliveryTime") + ") - " + s(d, "address") + ".";
        }
        if (body != null) notifiers.sendSms(to, body, event, d.get("userId"));
    }

    // ------------------------------------------------------------------ audit

    private void audit(String event, Map<String, Object> d) {
        Map<String, Object> rest = new LinkedHashMap<>(d);
        Object actorId = rest.remove("actorId");
        Object entity = rest.remove("entity");
        Object entityId = rest.remove("entityId");
        rest.remove("resetUrl"); // never persist secrets in the audit trail
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event);
        entry.put("actorId", actorId);
        entry.put("entity", entity);
        entry.put("entityId", entityId == null ? null : String.valueOf(entityId));
        entry.put("data", rest);
        documents.recordAudit(entry);
    }

    private static String s(Map<String, Object> d, String k) {
        Object v = d.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
