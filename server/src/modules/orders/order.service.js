import { query, withTransaction } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { hasPermission, PERMISSIONS as P } from '../../security/permissions.js';
import { invalidate } from '../../utils/cache.js';
import { AppError, badRequest, forbidden, notFound } from '../../utils/http.js';
import { clearPurchased } from '../cart/cart.service.js';
import { authorizePayment, METHOD_LABELS, recordPayment, toPayment } from '../payments/payment.service.js';
import { getSettings } from '../settings/settings.service.js';
import { computeTotals, promoApplies, unitPrice } from './pricing.js';

/** Allowed order lifecycle transitions (enforced server-side). */
export const ORDER_FLOW = {
  pending: ['confirmed', 'cancelled'],
  confirmed: ['preparing', 'cancelled'],
  preparing: ['ready', 'cancelled'],
  ready: ['out_for_delivery'],
  out_for_delivery: ['delivered'],
  delivered: [],
  cancelled: [],
};

export const canTransition = (from, to) => ORDER_FLOW[from]?.includes(to) ?? false;

export const formatOrderNumber = (id, date = new Date()) => `FLW-${date.getFullYear()}-${String(id).padStart(5, '0')}`;

const kigaliToday = () => new Date(Date.now() + 2 * 3600_000); // Africa/Kigali = UTC+2, no DST

export function validateDeliverySlot(dateStr, cutoffHour) {
  const now = kigaliToday();
  const today = now.toISOString().slice(0, 10);
  if (dateStr < today) throw badRequest('Delivery date cannot be in the past');
  const max = new Date(now.getTime() + 60 * 86_400_000).toISOString().slice(0, 10);
  if (dateStr > max) throw badRequest('Delivery can be scheduled at most 60 days ahead');
  if (dateStr === today && now.getUTCHours() >= cutoffHour) {
    throw badRequest(`Same-day orders close at ${cutoffHour}:00. Please choose tomorrow or later.`);
  }
}

const composeAddress = (d) =>
  [d.street, d.sector, d.district, d.province].filter(Boolean).join(', ');

function toOrder(o) {
  return {
    id: o.id,
    orderNumber: o.order_number,
    userId: o.user_id,
    customerName: o.customer_name,
    email: o.email,
    phone: o.phone,
    subtotal: o.subtotal,
    discount: o.discount,
    giftTotal: o.gift_total,
    deliveryFee: o.delivery_fee,
    total: o.total_amount,
    address: {
      province: o.province, district: o.district, sector: o.sector, street: o.street,
      locationDescription: o.location_description, full: o.delivery_address,
    },
    deliveryDate: o.delivery_date,
    deliveryTime: o.delivery_time,
    instructions: o.instructions,
    recipientName: o.recipient_name,
    messageType: o.message_type,
    giftMessage: o.gift_message,
    giftOptions: o.gift_options,
    paymentMethod: o.payment_method,
    paymentMethodLabel: METHOD_LABELS[o.payment_method],
    status: o.status,
    paymentStatus: o.payment_status,
    createdAt: o.created_at,
    updatedAt: o.updated_at,
    itemCount: o.item_count,
    firstItem: o.first_item,
    firstImage: o.first_image,
    deliveryStatus: o.delivery_status,
    deliveryStaff: o.staff_first ? `${o.staff_first} ${o.staff_last}` : null,
  };
}

// ---------------------------------------------------------------------------
// Checkout
// ---------------------------------------------------------------------------
export async function createOrder(userId, input) {
  const settings = await getSettings();
  validateDeliverySlot(input.delivery.date, settings.delivery.sameDayCutoffHour);
  if (!settings.delivery.timeSlots.includes(input.delivery.time)) throw badRequest('Choose one of the available delivery times');

  const result = await withTransaction(async (db) => {
    // Lock the product rows so concurrent checkouts can't oversell stock.
    const { rows: lines } = await db.query(
      `SELECT ci.product_id, ci.quantity, p.name, p.price, p.discount_percent, p.stock, p.status, p.image_url
       FROM cart_items ci
       JOIN cart c ON c.id = ci.cart_id
       JOIN products p ON p.id = ci.product_id
       WHERE c.user_id = $1 AND ci.saved_for_later = FALSE
       ORDER BY p.id
       FOR UPDATE OF p`,
      [userId],
    );
    if (!lines.length) throw badRequest('Your cart is empty');
    for (const l of lines) {
      if (l.status !== 'active') throw badRequest(`${l.name} is no longer available - please remove it from your cart`);
      if (l.quantity > l.stock) throw badRequest(`Only ${l.stock} ${l.name} left in stock`);
    }

    const { rows: gifts } = input.giftOptions.length
      ? await db.query('SELECT code, name, icon, price FROM gift_options WHERE active AND code = ANY($1)', [input.giftOptions])
      : { rows: [] };
    const giftTotal = gifts.reduce((s, g) => s + Number(g.price), 0);

    let promo = null;
    if (input.promoCode) {
      const { rows } = await db.query('SELECT * FROM promotions WHERE UPPER(code) = UPPER($1)', [input.promoCode]);
      promo = rows[0] || null;
      const pre = computeTotals({ lines: lines.map(asPricingLine), settings: settings.delivery });
      if (!promoApplies(promo, pre.subtotal)) throw badRequest('This promo code is invalid, expired, or below its minimum order');
    }

    const totals = computeTotals({ lines: lines.map(asPricingLine), settings: settings.delivery, giftTotal, promo });
    const d = input.delivery;
    const { rows: [order] } = await db.query(
      `INSERT INTO orders (user_id, subtotal, discount, gift_total, delivery_fee, total_amount, promotion_id,
          customer_name, email, phone, province, district, sector, street, location_description, delivery_address,
          delivery_date, delivery_time, instructions, recipient_name, message_type, gift_message, gift_options, payment_method)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24)
       RETURNING *`,
      [
        userId, totals.subtotal, totals.discount, giftTotal, totals.deliveryFee, totals.total, promo?.id ?? null,
        input.contact.fullName, input.contact.email, input.contact.phone,
        d.province, d.district, d.sector, d.street, d.locationDescription || null, composeAddress(d),
        d.date, d.time, input.instructions || null,
        input.message?.recipientName || null, input.message?.type || null, input.message?.text || null,
        JSON.stringify(gifts), input.payment.method,
      ],
    );
    const orderNumber = formatOrderNumber(order.id);
    await db.query('UPDATE orders SET order_number = $1 WHERE id = $2', [orderNumber, order.id]);
    order.order_number = orderNumber;

    for (const l of lines) {
      await db.query(
        `INSERT INTO order_items (order_id, product_id, product_name, image_url, quantity, price) VALUES ($1,$2,$3,$4,$5,$6)`,
        [order.id, l.product_id, l.name, l.image_url, l.quantity, unitPrice(l.price, l.discount_percent)],
      );
      await db.query('UPDATE products SET stock = stock - $1, sold_count = sold_count + $1 WHERE id = $2', [
        l.quantity,
        l.product_id,
      ]);
    }
    await db.query(`INSERT INTO order_status_history (order_id, status, changed_by, note) VALUES ($1,'pending',$2,'Order placed')`, [
      order.id,
      userId,
    ]);
    await db.query(
      `INSERT INTO deliveries (order_id, delivery_address, delivery_date, delivery_time) VALUES ($1,$2,$3,$4)`,
      [order.id, order.delivery_address, d.date, d.time],
    );

    // Charge the customer last: a decline throws and rolls the whole order back.
    const charge = authorizePayment(input.payment);
    const payment = await recordPayment(db, order.id, input.payment.method, totals.total, charge);
    if (charge.status === 'paid') {
      await db.query(`UPDATE orders SET payment_status = 'paid' WHERE id = $1`, [order.id]);
      order.payment_status = 'paid';
    }

    await clearPurchased(db, userId);

    if (input.saveAddress) {
      await db.query(
        `INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, location_description, is_default)
         VALUES ($1,'Saved',$2,$3,$4,$5,$6,$7,$8, NOT EXISTS (SELECT 1 FROM addresses WHERE user_id = $1))`,
        [userId, input.contact.fullName, input.contact.phone, d.province, d.district, d.sector, d.street, d.locationDescription || null],
      );
    }
    return { order, lines, payment };
  });

  invalidate('products:');
  const { order, lines, payment } = result;
  publish('order.placed', {
    actorId: userId, entity: 'order', entityId: order.id, userId, orderId: order.id,
    orderNumber: order.order_number, customerName: order.customer_name, email: order.email, phone: order.phone,
    total: order.total_amount, deliveryDate: order.delivery_date, deliveryTime: order.delivery_time,
    address: order.delivery_address, items: lines.map((l) => ({ name: l.name, quantity: l.quantity })),
  });
  if (payment.payment_status === 'paid') {
    publish('payment.completed', {
      actorId: userId, entity: 'payment', entityId: payment.id, userId, email: order.email,
      orderNumber: order.order_number, amount: payment.amount, method: METHOD_LABELS[payment.payment_method],
      reference: payment.transaction_reference,
    });
  }
  return { order: toOrder(order), payment: toPayment(payment) };
}

const asPricingLine = (l) => ({ price: l.price, discountPercent: l.discount_percent, quantity: l.quantity });

/** Checkout preview: totals for the current cart + gifts + promo, without writing anything. */
export async function quoteCheckout(userId, { giftOptions = [], promoCode }) {
  const settings = await getSettings();
  const { rows: lines } = await query(
    `SELECT ci.quantity, p.price, p.discount_percent FROM cart_items ci JOIN cart c ON c.id = ci.cart_id
     JOIN products p ON p.id = ci.product_id
     WHERE c.user_id = $1 AND ci.saved_for_later = FALSE AND p.status = 'active'`,
    [userId],
  );
  const { rows: gifts } = giftOptions.length
    ? await query('SELECT price FROM gift_options WHERE active AND code = ANY($1)', [giftOptions])
    : { rows: [] };
  const giftTotal = gifts.reduce((s, g) => s + Number(g.price), 0);
  let promo = null;
  let promoMessage = null;
  if (promoCode) {
    const { rows } = await query('SELECT * FROM promotions WHERE UPPER(code) = UPPER($1)', [promoCode]);
    promo = rows[0] || null;
    const pre = computeTotals({ lines: lines.map(asPricingLine), settings: settings.delivery });
    if (!promoApplies(promo, pre.subtotal)) {
      promoMessage = promo && promo.min_order > pre.subtotal
        ? `Spend RWF ${Number(promo.min_order).toLocaleString()} or more to use this code`
        : 'This promo code is invalid or expired';
      promo = null;
    }
  }
  const totals = computeTotals({ lines: lines.map(asPricingLine), settings: settings.delivery, giftTotal, promo });
  return { ...totals, promo: promo ? { code: promo.code, title: promo.title, percent: promo.discount_percent } : null, promoMessage };
}

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------
const LIST_SELECT = `
  SELECT o.*,
    (SELECT SUM(quantity) FROM order_items WHERE order_id = o.id) AS item_count,
    (SELECT product_name FROM order_items WHERE order_id = o.id ORDER BY id LIMIT 1) AS first_item,
    (SELECT image_url FROM order_items WHERE order_id = o.id ORDER BY id LIMIT 1) AS first_image,
    d.delivery_status, s.first_name AS staff_first, s.last_name AS staff_last
  FROM orders o
  LEFT JOIN deliveries d ON d.order_id = o.id
  LEFT JOIN users s ON s.id = d.staff_id`;

export async function listOrders({ userId, status, search, from, to, page, limit, offset }) {
  const params = [];
  const where = [];
  const add = (cond, v) => { params.push(v); where.push(cond.replaceAll('?', `$${params.length}`)); };
  if (userId) add('o.user_id = ?', userId);
  if (status === 'active') where.push(`o.status NOT IN ('delivered','cancelled')`);
  else if (status) add('o.status = ?', status);
  if (search) add('(o.order_number ILIKE ? OR o.customer_name ILIKE ? OR o.phone ILIKE ?)', `%${search}%`);
  if (from) add('o.created_at >= ?::date', from);
  if (to) add(`o.created_at < (?::date + INTERVAL '1 day')`, to);
  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const [{ rows }, { rows: c }] = await Promise.all([
    query(`${LIST_SELECT} ${whereSql} ORDER BY o.created_at DESC LIMIT ${limit} OFFSET ${offset}`, params),
    query(`SELECT COUNT(*) AS total FROM orders o ${whereSql}`, params),
  ]);
  return { items: rows.map(toOrder), total: c[0].total, page, pages: Math.max(1, Math.ceil(c[0].total / limit)) };
}

export async function getOrder(id, user) {
  const { rows } = await query(`${LIST_SELECT} WHERE o.id = $1`, [id]);
  const o = rows[0];
  if (!o) throw notFound('Order not found');
  // Object-level authorization: customers only see their own orders.
  if (o.user_id !== user.id && !hasPermission(user.role, P.ORDER_READ_ANY)) throw notFound('Order not found');

  const [items, history, delivery, payments] = await Promise.all([
    query(
      `SELECT oi.*, p.slug FROM order_items oi LEFT JOIN products p ON p.id = oi.product_id WHERE oi.order_id = $1 ORDER BY oi.id`,
      [id],
    ),
    query(
      `SELECT h.*, u.first_name, u.last_name, u.role FROM order_status_history h LEFT JOIN users u ON u.id = h.changed_by
       WHERE h.order_id = $1 ORDER BY h.created_at, h.id`,
      [id],
    ),
    query(
      `SELECT d.*, u.first_name, u.last_name, u.phone AS staff_phone FROM deliveries d LEFT JOIN users u ON u.id = d.staff_id
       WHERE d.order_id = $1`,
      [id],
    ),
    query('SELECT * FROM payments WHERE order_id = $1 ORDER BY payment_date DESC', [id]),
  ]);
  const d = delivery.rows[0];
  return {
    ...toOrder(o),
    items: items.rows.map((i) => ({
      id: i.id, productId: i.product_id, slug: i.slug, name: i.product_name, imageUrl: i.image_url,
      quantity: i.quantity, price: i.price, lineTotal: i.price * i.quantity,
    })),
    history: history.rows.map((h) => ({
      status: h.status, note: h.note, at: h.created_at,
      by: h.first_name ? `${h.first_name} ${h.last_name}` : null, byRole: h.role,
    })),
    delivery: d && {
      id: d.id, status: d.delivery_status, staffId: d.staff_id,
      staffName: d.first_name ? `${d.first_name} ${d.last_name}` : null,
      staffPhone: d.staff_phone, notes: d.notes, deliveredAt: d.delivered_at,
    },
    payments: payments.rows.map(toPayment),
  };
}

// ---------------------------------------------------------------------------
// Status changes (staff/admin, plus customer self-cancel)
// ---------------------------------------------------------------------------

/** Applies a status change inside an open transaction; returns the updated order row. */
export async function applyOrderStatus(db, order, next, actorId, note) {
  if (!canTransition(order.status, next)) {
    throw badRequest(`Cannot move an order from "${order.status}" to "${next}"`);
  }
  const { rows } = await db.query('UPDATE orders SET status = $1, updated_at = NOW() WHERE id = $2 RETURNING *', [next, order.id]);
  await db.query('INSERT INTO order_status_history (order_id, status, changed_by, note) VALUES ($1,$2,$3,$4)', [
    order.id, next, actorId, note || null,
  ]);

  if (next === 'cancelled') {
    // return stock and refund anything already paid
    await db.query(
      `UPDATE products p SET stock = p.stock + oi.quantity, sold_count = GREATEST(p.sold_count - oi.quantity, 0)
       FROM order_items oi WHERE oi.order_id = $1 AND oi.product_id = p.id`,
      [order.id],
    );
    await db.query(`UPDATE payments SET payment_status = 'refunded' WHERE order_id = $1 AND payment_status = 'paid'`, [order.id]);
    await db.query(`UPDATE payments SET payment_status = 'failed' WHERE order_id = $1 AND payment_status = 'pending'`, [order.id]);
    await db.query(
      `UPDATE orders SET payment_status = CASE WHEN payment_status = 'paid' THEN 'refunded' ELSE 'failed' END WHERE id = $1`,
      [order.id],
    );
    invalidate('products:');
  }
  if (next === 'out_for_delivery') {
    await db.query(
      `UPDATE deliveries SET delivery_status = 'on_the_way', updated_at = NOW()
       WHERE order_id = $1 AND delivery_status IN ('pending','assigned','picked_up')`,
      [order.id],
    );
  }
  if (next === 'delivered') {
    await db.query(
      `UPDATE deliveries SET delivery_status = 'delivered', delivered_at = NOW(), updated_at = NOW() WHERE order_id = $1`,
      [order.id],
    );
    // cash collected at the door
    await db.query(
      `UPDATE payments SET payment_status = 'paid', payment_date = NOW() WHERE order_id = $1 AND payment_status = 'pending'`,
      [order.id],
    );
    await db.query(`UPDATE orders SET payment_status = 'paid' WHERE id = $1 AND payment_status = 'pending'`, [order.id]);
  }
  return rows[0];
}

export function announceStatus(order, actorId, note) {
  publish('order.status_changed', {
    actorId, entity: 'order', entityId: order.id, userId: order.user_id, orderId: order.id,
    orderNumber: order.order_number, customerName: order.customer_name, email: order.email, phone: order.phone,
    status: order.status, note,
  });
}

export async function updateOrderStatus(orderId, next, actor, note) {
  const updated = await withTransaction(async (db) => {
    const { rows } = await db.query('SELECT * FROM orders WHERE id = $1 FOR UPDATE', [orderId]);
    if (!rows[0]) throw notFound('Order not found');
    return applyOrderStatus(db, rows[0], next, actor.id, note);
  });
  announceStatus(updated, actor.id, note);
  return getOrder(orderId, actor);
}

export async function cancelOwnOrder(orderId, user) {
  const { rows } = await query('SELECT user_id, status FROM orders WHERE id = $1', [orderId]);
  if (!rows[0] || rows[0].user_id !== user.id) throw notFound('Order not found');
  if (rows[0].status !== 'pending') {
    throw new AppError(409, 'This order is already being prepared and can no longer be cancelled online. Please contact us.');
  }
  return updateOrderStatus(orderId, 'cancelled', user, 'Cancelled by customer');
}

export async function assertOwnsOrder(orderId, userId) {
  const { rows } = await query('SELECT user_id FROM orders WHERE id = $1', [orderId]);
  if (!rows[0] || rows[0].user_id !== userId) throw forbidden();
}
