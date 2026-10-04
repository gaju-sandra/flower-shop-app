package rw.bloomco.messaging;

import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import rw.bloomco.config.AppProperties;
import rw.bloomco.document.DocumentStore;

/** Email (SMTP via JavaMail) and SMS delivery. Without credentials, messages are logged instead of sent. */
@Component
public class Notifiers {

    private static final Logger log = LoggerFactory.getLogger(Notifiers.class);
    private final AppProperties props;
    private final DocumentStore documents;
    private final JavaMailSenderImpl mail;

    public Notifiers(AppProperties props, DocumentStore documents) {
        this.props = props;
        this.documents = documents;
        this.mail = props.smtp().enabled() ? buildSender(props.smtp()) : null;
    }

    private static JavaMailSenderImpl buildSender(AppProperties.Smtp smtp) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(smtp.username() != null && !smtp.username().isBlank()));
        p.put("mail.smtp.starttls.enable", String.valueOf(smtp.port() != 465));
        p.put("mail.smtp.ssl.enable", String.valueOf(smtp.port() == 465));
        p.put("mail.smtp.connectiontimeout", "8000");
        p.put("mail.smtp.timeout", "8000");
        return sender;
    }

    public void sendEmail(String to, String subject, String html, String event, Object userId) {
        if (to == null || to.isBlank()) return;
        try {
            String status;
            if (mail != null) {
                MimeMessage msg = mail.createMimeMessage();
                var helper = new MimeMessageHelper(msg, "UTF-8");
                helper.setFrom(props.smtp().from());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(wrapHtml(subject, html), true);
                mail.send(msg);
                status = "sent";
            } else {
                log.info("📧 [email:{}] to={} subject=\"{}\"", event, to, subject);
                status = "logged";
            }
            documents.recordNotification(entry("email", to, subject, html, event, userId, status, null));
        } catch (Exception e) {
            documents.recordNotification(entry("email", to, subject, null, event, userId, "failed", e.getMessage()));
            throw new IllegalStateException("Email failed: " + e.getMessage(), e);
        }
    }

    /** Plug a real SMS provider (e.g. Africa's Talking) here when SMS_API_KEY is configured. */
    public void sendSms(String to, String body, String event, Object userId) {
        if (to == null || to.isBlank()) return;
        String status = props.sms().apiKey() != null && !props.sms().apiKey().isBlank() ? "sent" : "logged";
        log.info("📱 [sms:{}] to={} \"{}\"", event, to, body);
        documents.recordNotification(entry("sms", to, null, body, event, userId, status, null));
    }

    private static Map<String, Object> entry(String channel, String to, String subject, String body, String event,
            Object userId, String status, String error) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("channel", channel);
        e.put("to", to);
        e.put("subject", subject);
        e.put("body", body);
        e.put("event", event);
        e.put("userId", userId);
        e.put("status", status);
        e.put("error", error);
        return e;
    }

    private static String wrapHtml(String title, String body) {
        return """
            <div style="font-family:Georgia,serif;max-width:560px;margin:auto;background:#fffaf5;border-radius:16px;overflow:hidden;border:1px solid #f3d9e0">
              <div style="background:linear-gradient(135deg,#e11d74,#c084fc);padding:24px;color:#fff;font-size:22px">🌸 Bloom &amp; Co.</div>
              <div style="padding:24px;color:#3f2a33;font-size:15px;line-height:1.6"><h2 style="color:#be185d;margin-top:0">%s</h2>%s</div>
              <div style="padding:16px 24px;font-size:12px;color:#9b7b86;background:#fdf2f6">Beautiful flowers, delivered with love · Kigali, Rwanda</div>
            </div>""".formatted(title, body);
    }
}
