package rw.bloomco.payment;

import java.security.SecureRandom;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Db;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Row;
import rw.bloomco.messaging.EventBus;

/**
 * Payment gateway adapter (simulated). The interface mirrors what a real MTN MoMo /
 * Airtel Money / card processor integration exposes, so a real provider can be dropped in.
 *
 * Demo rules: a mobile-money number ending in 0000, or card 4000 0000 0000 0002, is declined.
 */
@Service
public class PaymentService {

    public static final Map<String, String> METHOD_LABELS = Map.of(
            "mtn_momo", "MTN Mobile Money",
            "airtel_money", "Airtel Money",
            "card", "Visa / Mastercard",
            "cash_on_delivery", "Cash on Delivery");

    private static final Pattern MTN = Pattern.compile("^(\\+?250|0)7[89]\\d{7}$");
    private static final Pattern AIRTEL = Pattern.compile("^(\\+?250|0)7[23]\\d{7}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Payment details sent at checkout. Card data is validated and discarded - only the last 4 digits are kept. */
    public record PaymentInput(String method, String phone, String cardName, String cardNumber, String expiry, String cvc) {}

    public record Charge(String status, String reference, String payerPhone, String cardLast4) {}

    private final Db db;
    private final EventBus events;

    public PaymentService(Db db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    public static boolean luhnValid(String number) {
        String digits = number.replaceAll("\\D", "");
        if (digits.length() < 12 || digits.length() > 19) return false;
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static String reference(String prefix) {
        byte[] b = new byte[3];
        RANDOM.nextBytes(b);
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase() + "-" + HexFormat.of().formatHex(b).toUpperCase();
    }

    /** Validates the payment details and "charges" them. Throws 402 on decline. */
    public static Charge authorize(PaymentInput p) {
        String method = p.method();
        if ("mtn_momo".equals(method) || "airtel_money".equals(method)) {
            String phone = p.phone() == null ? "" : p.phone().replaceAll("[\\s-]", "");
            boolean mtn = "mtn_momo".equals(method);
            if (!(mtn ? MTN : AIRTEL).matcher(phone).matches()) {
                throw ApiException.badRequest("Enter a valid " + METHOD_LABELS.get(method) + " number (" + (mtn ? "078/079" : "072/073") + "…)");
            }
            if (phone.endsWith("0000")) throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "The mobile money payment was declined. Please try another number.");
            return new Charge("paid", reference(mtn ? "MOMO" : "AIRTEL"), phone, null);
        }
        if ("card".equals(method)) {
            String number = p.cardNumber() == null ? "" : p.cardNumber().replaceAll("\\D", "");
            if (!luhnValid(number)) throw ApiException.badRequest("The card number is not valid");
            if (!validExpiry(p.expiry())) throw ApiException.badRequest("The card has expired or the expiry date is invalid");
            if (p.cvc() == null || !p.cvc().matches("^\\d{3,4}$")) throw ApiException.badRequest("Enter the 3 or 4 digit security code");
            if ("4000000000000002".equals(number)) throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Your card was declined. Please use another card.");
            return new Charge("paid", reference("CARD"), null, number.substring(number.length() - 4));
        }
        if ("cash_on_delivery".equals(method)) return new Charge("pending", reference("COD"), null, null);
        throw ApiException.badRequest("Unsupported payment method");
    }

    private static boolean validExpiry(String expiry) {
        if (expiry == null || !expiry.matches("^\\d{1,2}\\s*/\\s*\\d{2}$")) return false;
        String[] parts = expiry.split("/");
        int mm = Integer.parseInt(parts[0].trim());
        int yy = Integer.parseInt(parts[1].trim());
        if (mm < 1 || mm > 12) return false;
        return !YearMonth.of(2000 + yy, mm).isBefore(YearMonth.now());
    }

    public Row record(int orderId, String method, long amount, Charge c) {
        return db.rows("""
                INSERT INTO payments (order_id, payment_method, amount, transaction_reference, payer_phone, card_last4, payment_status)
                VALUES (?,?,?,?,?,?,?) RETURNING *""",
                orderId, method, amount, c.reference(), c.payerPhone(), c.cardLast4(), c.status()).get(0);
    }

    public static Map<String, Object> toPayment(Row p) {
        return Json.obj(
                "id", p.integer("id"),
                "orderId", p.integer("order_id"),
                "orderNumber", p.has("order_number") ? p.str("order_number") : null,
                "customer", p.has("customer_name") ? p.str("customer_name") : null,
                "method", p.str("payment_method"),
                "methodLabel", METHOD_LABELS.get(p.str("payment_method")),
                "amount", p.money("amount"),
                "reference", p.str("transaction_reference"),
                "payerPhone", p.str("payer_phone"),
                "cardLast4", p.str("card_last4"),
                "status", p.str("payment_status"),
                "date", p.ts("payment_date"));
    }

    public Map<String, Object> list(String status, String method, Paging paging) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (status != null) { where.add("p.payment_status = ?"); params.add(status); }
        if (method != null) { where.add("p.payment_method = ?"); params.add(method); }
        String whereSql = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Object[] args = params.toArray();
        var items = db.rows("SELECT p.*, o.order_number, o.customer_name FROM payments p JOIN orders o ON o.id = p.order_id "
                + whereSql + " ORDER BY p.payment_date DESC LIMIT " + paging.limit() + " OFFSET " + paging.offset(), args)
                .stream().map(PaymentService::toPayment).toList();
        Row a = db.one("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(amount) FILTER (WHERE payment_status = 'paid'), 0) AS collected,
                       COALESCE(SUM(amount) FILTER (WHERE payment_status = 'pending'), 0) AS outstanding,
                       COALESCE(SUM(amount) FILTER (WHERE payment_status = 'refunded'), 0) AS refunded
                FROM payments p """ + " " + whereSql, args).orElseThrow();
        var result = paging.result(items, a.longOr0("total"));
        result.put("totals", Json.obj("collected", a.money("collected"), "outstanding", a.money("outstanding"), "refunded", a.money("refunded")));
        return result;
    }

    /** Admin: confirm a cash payment or refund a paid one. */
    public Map<String, Object> setStatus(int paymentId, String status, int actorId) {
        var rows = db.rows("UPDATE payments SET payment_status = ?, payment_date = NOW() WHERE id = ? RETURNING *", status, paymentId);
        if (rows.isEmpty()) throw ApiException.notFound("Payment not found");
        Row p = rows.get(0);
        Row o = db.rows("UPDATE orders SET payment_status = ?, updated_at = NOW() WHERE id = ? RETURNING *", status, p.integer("order_id")).get(0);
        events.publish("paid".equals(status) ? "payment.completed" : "payment." + status, Json.obj(
                "actorId", actorId, "entity", "payment", "entityId", p.integer("id"), "userId", o.integer("user_id"),
                "email", o.str("email"), "orderNumber", o.str("order_number"), "amount", p.money("amount"),
                "method", METHOD_LABELS.get(p.str("payment_method")), "reference", p.str("transaction_reference")));
        return Json.with(toPayment(p), "orderNumber", o.str("order_number"), "customer", o.str("customer_name"));
    }
}
