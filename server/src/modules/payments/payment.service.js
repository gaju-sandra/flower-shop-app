import crypto from 'node:crypto';
import { query } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { AppError, badRequest, notFound } from '../../utils/http.js';

/**
 * Payment gateway adapter (simulated).
 * The interface mirrors what a real MTN MoMo / Airtel Money / card processor
 * integration would expose, so a real provider can be dropped in later.
 *
 * Demo rules: a mobile-money number ending in 0000 or card 4000 0000 0000 0002
 * is declined - useful to show the failure path.
 */
export const METHOD_LABELS = {
  mtn_momo: 'MTN Mobile Money',
  airtel_money: 'Airtel Money',
  card: 'Visa / Mastercard',
  cash_on_delivery: 'Cash on Delivery',
};

const MTN = /^(\+?250|0)7[89]\d{7}$/;
const AIRTEL = /^(\+?250|0)7[23]\d{7}$/;

export function luhnValid(number) {
  const digits = String(number).replace(/\D/g, '');
  if (digits.length < 12 || digits.length > 19) return false;
  let sum = 0;
  for (let i = 0; i < digits.length; i += 1) {
    let d = Number(digits[digits.length - 1 - i]);
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}

const reference = (prefix) =>
  `${prefix}-${Date.now().toString(36).toUpperCase()}-${crypto.randomBytes(3).toString('hex').toUpperCase()}`;

/** Validates payment details and "charges" them. Throws 402 on decline. */
export function authorizePayment(payment) {
  const { method } = payment;
  if (method === 'mtn_momo' || method === 'airtel_money') {
    const phone = String(payment.phone || '').replace(/[\s-]/g, '');
    const pattern = method === 'mtn_momo' ? MTN : AIRTEL;
    if (!pattern.test(phone)) {
      throw badRequest(`Enter a valid ${METHOD_LABELS[method]} number (${method === 'mtn_momo' ? '078/079' : '072/073'}…)`);
    }
    if (phone.endsWith('0000')) throw new AppError(402, 'The mobile money payment was declined. Please try another number.');
    return { status: 'paid', reference: reference(method === 'mtn_momo' ? 'MOMO' : 'AIRTEL'), payerPhone: phone };
  }
  if (method === 'card') {
    const number = String(payment.cardNumber || '').replace(/\D/g, '');
    if (!luhnValid(number)) throw badRequest('The card number is not valid');
    const [mm, yy] = String(payment.expiry || '').split('/').map((s) => parseInt(s, 10));
    const expiry = new Date(2000 + yy, mm, 0, 23, 59);
    if (!mm || mm > 12 || !yy || expiry < new Date()) throw badRequest('The card has expired or the expiry date is invalid');
    if (!/^\d{3,4}$/.test(String(payment.cvc || ''))) throw badRequest('Enter the 3 or 4 digit security code');
    if (number === '4000000000000002') throw new AppError(402, 'Your card was declined. Please use another card.');
    // Only the last four digits ever leave this function - no PAN/CVC is stored.
    return { status: 'paid', reference: reference('CARD'), cardLast4: number.slice(-4) };
  }
  if (method === 'cash_on_delivery') {
    return { status: 'pending', reference: reference('COD') };
  }
  throw badRequest('Unsupported payment method');
}

export async function recordPayment(db, orderId, method, amount, result) {
  const { rows } = await db.query(
    `INSERT INTO payments (order_id, payment_method, amount, transaction_reference, payer_phone, card_last4, payment_status)
     VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
    [orderId, method, amount, result.reference, result.payerPhone || null, result.cardLast4 || null, result.status],
  );
  return rows[0];
}

export const toPayment = (p) => ({
  id: p.id,
  orderId: p.order_id,
  orderNumber: p.order_number,
  customer: p.customer_name,
  method: p.payment_method,
  methodLabel: METHOD_LABELS[p.payment_method],
  amount: p.amount,
  reference: p.transaction_reference,
  payerPhone: p.payer_phone,
  cardLast4: p.card_last4,
  status: p.payment_status,
  date: p.payment_date,
});

export async function listPayments({ status, method, page, limit, offset }) {
  const params = [];
  const where = [];
  if (status) { params.push(status); where.push(`p.payment_status = $${params.length}`); }
  if (method) { params.push(method); where.push(`p.payment_method = $${params.length}`); }
  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const [{ rows }, { rows: agg }] = await Promise.all([
    query(
      `SELECT p.*, o.order_number, o.customer_name FROM payments p JOIN orders o ON o.id = p.order_id
       ${whereSql} ORDER BY p.payment_date DESC LIMIT ${limit} OFFSET ${offset}`,
      params,
    ),
    query(
      `SELECT COUNT(*) AS total,
              COALESCE(SUM(amount) FILTER (WHERE payment_status = 'paid'), 0) AS collected,
              COALESCE(SUM(amount) FILTER (WHERE payment_status = 'pending'), 0) AS outstanding,
              COALESCE(SUM(amount) FILTER (WHERE payment_status = 'refunded'), 0) AS refunded
       FROM payments p ${whereSql}`,
      params,
    ),
  ]);
  const a = agg[0];
  return {
    items: rows.map(toPayment),
    total: a.total,
    page,
    pages: Math.max(1, Math.ceil(a.total / limit)),
    totals: { collected: a.collected, outstanding: a.outstanding, refunded: a.refunded },
  };
}

/** Admin: confirm a cash payment or refund a paid one. */
export async function setPaymentStatus(paymentId, status, actorId) {
  const { rows } = await query(
    `UPDATE payments SET payment_status = $1, payment_date = NOW() WHERE id = $2 RETURNING *`,
    [status, paymentId],
  );
  const p = rows[0];
  if (!p) throw notFound('Payment not found');
  const { rows: orders } = await query(
    'UPDATE orders SET payment_status = $1, updated_at = NOW() WHERE id = $2 RETURNING *',
    [status, p.order_id],
  );
  const o = orders[0];
  publish(status === 'paid' ? 'payment.completed' : `payment.${status}`, {
    actorId, entity: 'payment', entityId: p.id, userId: o.user_id, email: o.email,
    orderNumber: o.order_number, amount: p.amount, method: METHOD_LABELS[p.payment_method], reference: p.transaction_reference,
  });
  return toPayment({ ...p, order_number: o.order_number, customer_name: o.customer_name });
}
