import { query, withTransaction } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { badRequest, notFound } from '../../utils/http.js';
import { announceStatus, applyOrderStatus } from '../orders/order.service.js';

export const DELIVERY_FLOW = {
  pending: ['assigned'],
  assigned: ['picked_up', 'assigned'],
  picked_up: ['on_the_way'],
  on_the_way: ['delivered'],
  delivered: [],
};

const toDelivery = (d) => ({
  id: d.id,
  orderId: d.order_id,
  orderNumber: d.order_number,
  orderStatus: d.order_status,
  customer: d.customer_name,
  phone: d.phone,
  recipientName: d.recipient_name,
  address: d.delivery_address,
  locationDescription: d.location_description,
  instructions: d.instructions,
  date: d.delivery_date,
  time: d.delivery_time,
  status: d.delivery_status,
  staffId: d.staff_id,
  staffName: d.staff_first ? `${d.staff_first} ${d.staff_last}` : null,
  notes: d.notes,
  total: d.total_amount,
  paymentMethod: d.payment_method,
  paymentStatus: d.payment_status,
  deliveredAt: d.delivered_at,
});

const SELECT = `
  SELECT d.*, o.order_number, o.status AS order_status, o.customer_name, o.phone, o.recipient_name,
         o.location_description, o.instructions, o.total_amount, o.payment_method, o.payment_status,
         u.first_name AS staff_first, u.last_name AS staff_last
  FROM deliveries d JOIN orders o ON o.id = d.order_id LEFT JOIN users u ON u.id = d.staff_id`;

export async function listDeliveries({ status, date, staffId, page, limit, offset }) {
  const params = [];
  const where = [`o.status <> 'cancelled'`];
  if (status) { params.push(status); where.push(`d.delivery_status = $${params.length}`); }
  if (date) { params.push(date); where.push(`d.delivery_date = $${params.length}`); }
  if (staffId) { params.push(staffId); where.push(`d.staff_id = $${params.length}`); }
  const whereSql = `WHERE ${where.join(' AND ')}`;
  const [{ rows }, { rows: c }] = await Promise.all([
    query(
      `${SELECT} ${whereSql}
       ORDER BY (d.delivery_status = 'delivered'), d.delivery_date, d.delivery_time LIMIT ${limit} OFFSET ${offset}`,
      params,
    ),
    query(`SELECT COUNT(*) AS total FROM deliveries d JOIN orders o ON o.id = d.order_id ${whereSql}`, params),
  ]);
  return { items: rows.map(toDelivery), total: c[0].total, page, pages: Math.max(1, Math.ceil(c[0].total / limit)) };
}

export async function listCouriers() {
  const { rows } = await query(
    `SELECT u.id, u.first_name, u.last_name, u.phone, u.staff_role,
       (SELECT COUNT(*) FROM deliveries d WHERE d.staff_id = u.id AND d.delivery_status IN ('assigned','picked_up','on_the_way')) AS active_jobs
     FROM users u WHERE u.role = 'staff' AND u.status = 'active'
     ORDER BY (u.staff_role = 'delivery_staff') DESC, u.first_name`,
  );
  return rows.map((r) => ({
    id: r.id, name: `${r.first_name} ${r.last_name}`, phone: r.phone, staffRole: r.staff_role, activeJobs: r.active_jobs,
  }));
}

async function loadForUpdate(db, deliveryId) {
  const { rows } = await db.query(
    `SELECT d.*, o.status AS order_status FROM deliveries d JOIN orders o ON o.id = d.order_id WHERE d.id = $1 FOR UPDATE OF d, o`,
    [deliveryId],
  );
  if (!rows[0]) throw notFound('Delivery not found');
  return rows[0];
}

export async function assignDelivery(deliveryId, staffId, actorId) {
  const { delivery, staff } = await withTransaction(async (db) => {
    const d = await loadForUpdate(db, deliveryId);
    if (!['pending', 'assigned'].includes(d.delivery_status)) throw badRequest('This delivery is already on its way');
    if (d.order_status === 'cancelled') throw badRequest('This order was cancelled');
    const { rows: s } = await db.query(`SELECT * FROM users WHERE id = $1 AND role = 'staff' AND status = 'active'`, [staffId]);
    if (!s[0]) throw badRequest('Choose an active staff member');
    const { rows } = await db.query(
      `UPDATE deliveries SET staff_id = $1, delivery_status = 'assigned', updated_at = NOW() WHERE id = $2 RETURNING *`,
      [staffId, deliveryId],
    );
    return { delivery: rows[0], staff: s[0] };
  });
  const { rows: o } = await query('SELECT order_number FROM orders WHERE id = $1', [delivery.order_id]);
  publish('delivery.assigned', {
    actorId, entity: 'delivery', entityId: delivery.id, orderNumber: o[0].order_number,
    staffId, staffPhone: staff.phone, address: delivery.delivery_address,
    deliveryDate: delivery.delivery_date, deliveryTime: delivery.delivery_time,
  });
  return getDelivery(deliveryId);
}

/**
 * Courier progress. Keeps the order in sync:
 *   picked_up  -> requires order "ready", moves order to out_for_delivery
 *   delivered  -> order delivered (and cash-on-delivery marked paid)
 */
export async function updateDeliveryStatus(deliveryId, next, actor, notes) {
  const changedOrder = await withTransaction(async (db) => {
    const d = await loadForUpdate(db, deliveryId);
    if (!DELIVERY_FLOW[d.delivery_status]?.includes(next)) {
      throw badRequest(`Cannot move a delivery from "${d.delivery_status}" to "${next}"`);
    }
    if (next === 'picked_up' && !['ready', 'out_for_delivery'].includes(d.order_status)) {
      throw badRequest('The bouquet must be marked "Ready" before it can be picked up');
    }
    await db.query(
      `UPDATE deliveries SET delivery_status = $1, notes = COALESCE($2, notes), updated_at = NOW(),
         delivered_at = CASE WHEN $1 = 'delivered' THEN NOW() ELSE delivered_at END
       WHERE id = $3`,
      [next, notes || null, deliveryId],
    );
    const { rows } = await db.query('SELECT * FROM orders WHERE id = $1', [d.order_id]);
    const order = rows[0];
    if (next === 'picked_up' && order.status === 'ready') {
      return applyOrderStatus(db, order, 'out_for_delivery', actor.id, 'Courier picked up the flowers');
    }
    if (next === 'delivered' && order.status !== 'delivered') {
      if (order.status === 'ready') await applyOrderStatus(db, order, 'out_for_delivery', actor.id);
      return applyOrderStatus(db, { ...order, status: 'out_for_delivery' }, 'delivered', actor.id, notes || 'Delivered to recipient');
    }
    return null;
  });
  if (changedOrder) announceStatus(changedOrder, actor.id);
  publish('delivery.status_changed', { actorId: actor.id, entity: 'delivery', entityId: deliveryId, status: next });
  return getDelivery(deliveryId);
}

export async function getDelivery(id) {
  const { rows } = await query(`${SELECT} WHERE d.id = $1`, [id]);
  if (!rows[0]) throw notFound('Delivery not found');
  return toDelivery(rows[0]);
}
