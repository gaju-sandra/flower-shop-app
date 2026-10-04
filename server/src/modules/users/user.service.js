import bcrypt from 'bcryptjs';
import { query } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { badRequest, conflict, notFound } from '../../utils/http.js';
import { getUserById, hashPassword, toPublicUser } from '../auth/auth.service.js';

async function assertEmailFree(email, exceptId = 0) {
  const { rows } = await query('SELECT 1 FROM users WHERE LOWER(email) = LOWER($1) AND id <> $2', [email, exceptId]);
  if (rows.length) throw conflict('Another account already uses this email');
}

// ---------- own profile ----------
export async function updateProfile(userId, { firstName, lastName, email, phone }) {
  if (email) await assertEmailFree(email, userId);
  const { rows } = await query(
    `UPDATE users SET first_name = COALESCE($1, first_name), last_name = COALESCE($2, last_name),
       email = COALESCE($3, email), phone = COALESCE($4, phone), updated_at = NOW()
     WHERE id = $5 RETURNING *`,
    [firstName ?? null, lastName ?? null, email ?? null, phone ?? null, userId],
  );
  publish('user.profile_updated', { actorId: userId, entity: 'user', entityId: userId });
  return toPublicUser(rows[0]);
}

export async function setAvatar(userId, url) {
  const { rows } = await query('UPDATE users SET avatar_url = $1, updated_at = NOW() WHERE id = $2 RETURNING *', [url, userId]);
  return toPublicUser(rows[0]);
}

export async function changePassword(userId, { currentPassword, newPassword }) {
  const user = await getUserById(userId);
  if (user.password_hash) {
    if (!currentPassword || !(await bcrypt.compare(currentPassword, user.password_hash))) {
      throw badRequest('Your current password is incorrect');
    }
  }
  await query('UPDATE users SET password_hash = $1, updated_at = NOW() WHERE id = $2', [await hashPassword(newPassword), userId]);
  publish('user.password_changed', { actorId: userId, entity: 'user', entityId: userId });
}

export async function customerDashboard(userId) {
  const [{ rows: s }, { rows: recent }] = await Promise.all([
    query(
      `SELECT
         (SELECT COUNT(*) FROM orders WHERE user_id = $1) AS total_orders,
         (SELECT COUNT(*) FROM orders WHERE user_id = $1 AND status NOT IN ('delivered','cancelled')) AS pending_orders,
         (SELECT COUNT(*) FROM orders WHERE user_id = $1 AND status = 'delivered') AS delivered_orders,
         (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE user_id = $1 AND status <> 'cancelled') AS total_spent,
         (SELECT COALESCE(SUM(ci.quantity),0) FROM cart_items ci JOIN cart c ON c.id = ci.cart_id
            WHERE c.user_id = $1 AND NOT ci.saved_for_later) AS cart_items,
         (SELECT COUNT(*) FROM wishlist WHERE user_id = $1) AS wishlist_items`,
      [userId],
    ),
    query(
      `SELECT o.id, o.order_number, o.total_amount, o.status, o.delivery_date, o.created_at,
         (SELECT string_agg(product_name, ', ' ORDER BY id) FROM order_items WHERE order_id = o.id) AS flowers,
         (SELECT SUM(quantity) FROM order_items WHERE order_id = o.id) AS quantity
       FROM orders o WHERE o.user_id = $1 ORDER BY o.created_at DESC LIMIT 5`,
      [userId],
    ),
  ]);
  const r = s[0];
  return {
    stats: {
      totalOrders: r.total_orders, pendingOrders: r.pending_orders, deliveredOrders: r.delivered_orders,
      totalSpent: r.total_spent, cartItems: Number(r.cart_items), wishlistItems: r.wishlist_items,
    },
    recentOrders: recent.map((o) => ({
      id: o.id, orderNumber: o.order_number, flowers: o.flowers, quantity: Number(o.quantity),
      total: o.total_amount, status: o.status, deliveryDate: o.delivery_date, createdAt: o.created_at,
    })),
  };
}

// ---------- addresses ----------
const toAddress = (a) => ({
  id: a.id, label: a.label, recipientName: a.recipient_name, phone: a.phone, province: a.province,
  district: a.district, sector: a.sector, street: a.street, locationDescription: a.location_description,
  isDefault: a.is_default,
});

export async function listAddresses(userId) {
  const { rows } = await query('SELECT * FROM addresses WHERE user_id = $1 ORDER BY is_default DESC, id', [userId]);
  return rows.map(toAddress);
}

export async function saveAddress(userId, data, addressId) {
  const { rows: existing } = await query('SELECT COUNT(*) AS n FROM addresses WHERE user_id = $1', [userId]);
  const makeDefault = data.isDefault || existing[0].n === 0;
  if (makeDefault) await query('UPDATE addresses SET is_default = FALSE WHERE user_id = $1', [userId]);
  const values = [
    data.label || 'Home', data.recipientName || null, data.phone || null, data.province, data.district,
    data.sector, data.street, data.locationDescription || null, makeDefault,
  ];
  if (addressId) {
    const { rowCount } = await query(
      `UPDATE addresses SET label=$1, recipient_name=$2, phone=$3, province=$4, district=$5, sector=$6, street=$7,
         location_description=$8, is_default = (is_default OR $9) WHERE id = $10 AND user_id = $11`,
      [...values, addressId, userId],
    );
    if (!rowCount) throw notFound('Address not found');
  } else {
    await query(
      `INSERT INTO addresses (label, recipient_name, phone, province, district, sector, street, location_description, is_default, user_id)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
      [...values, userId],
    );
  }
  return listAddresses(userId);
}

export async function deleteAddress(userId, addressId) {
  const { rows } = await query('DELETE FROM addresses WHERE id = $1 AND user_id = $2 RETURNING is_default', [addressId, userId]);
  if (!rows[0]) throw notFound('Address not found');
  if (rows[0].is_default) {
    await query(
      `UPDATE addresses SET is_default = TRUE WHERE id = (SELECT id FROM addresses WHERE user_id = $1 ORDER BY id LIMIT 1)`,
      [userId],
    );
  }
  return listAddresses(userId);
}

export async function setDefaultAddress(userId, addressId) {
  await query('UPDATE addresses SET is_default = FALSE WHERE user_id = $1', [userId]);
  const { rowCount } = await query('UPDATE addresses SET is_default = TRUE WHERE id = $1 AND user_id = $2', [addressId, userId]);
  if (!rowCount) throw notFound('Address not found');
  return listAddresses(userId);
}

// ---------- back office: customers & staff ----------
export async function listUsers({ role, search, status, page, limit, offset }) {
  const params = [];
  const where = [role === 'staff' ? `u.role = 'staff'` : `u.role = 'customer'`];
  if (search) {
    params.push(`%${search}%`);
    const n = params.length;
    where.push(`(u.first_name ILIKE $${n} OR u.last_name ILIKE $${n} OR u.email ILIKE $${n} OR u.phone ILIKE $${n})`);
  }
  if (status) { params.push(status); where.push(`u.status = $${params.length}`); }
  const whereSql = `WHERE ${where.join(' AND ')}`;
  const [{ rows }, { rows: c }] = await Promise.all([
    query(
      `SELECT u.*,
         (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count,
         (SELECT COALESCE(SUM(total_amount),0) FROM orders o WHERE o.user_id = u.id AND o.status <> 'cancelled') AS total_spent,
         (SELECT COUNT(*) FROM deliveries d WHERE d.staff_id = u.id AND d.delivery_status = 'delivered') AS deliveries_done
       FROM users u ${whereSql} ORDER BY u.created_at DESC LIMIT ${limit} OFFSET ${offset}`,
      params,
    ),
    query(`SELECT COUNT(*) AS total FROM users u ${whereSql}`, params),
  ]);
  return {
    items: rows.map((u) => ({
      ...toPublicUser(u),
      permissions: undefined,
      lastLoginAt: u.last_login_at,
      orderCount: u.order_count,
      totalSpent: u.total_spent,
      deliveriesDone: u.deliveries_done,
    })),
    total: c[0].total,
    page,
    pages: Math.max(1, Math.ceil(c[0].total / limit)),
  };
}

export async function customerDetail(id) {
  const user = await getUserById(id);
  if (!user || user.role !== 'customer') throw notFound('Customer not found');
  const [{ rows: orders }, addresses] = await Promise.all([
    query(
      `SELECT id, order_number, total_amount, status, payment_status, delivery_date, created_at
       FROM orders WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50`,
      [id],
    ),
    listAddresses(id),
  ]);
  return {
    ...toPublicUser(user),
    permissions: undefined,
    lastLoginAt: user.last_login_at,
    addresses,
    orders: orders.map((o) => ({
      id: o.id, orderNumber: o.order_number, total: o.total_amount, status: o.status,
      paymentStatus: o.payment_status, deliveryDate: o.delivery_date, createdAt: o.created_at,
    })),
  };
}

export async function adminUpdateUser(id, data, actorId, { role }) {
  const user = await getUserById(id);
  if (!user || (role === 'customer' ? user.role !== 'customer' : user.role !== 'staff')) throw notFound('User not found');
  if (data.email) await assertEmailFree(data.email, id);
  const { rows } = await query(
    `UPDATE users SET first_name = COALESCE($1, first_name), last_name = COALESCE($2, last_name),
       email = COALESCE($3, email), phone = COALESCE($4, phone),
       staff_role = CASE WHEN role = 'staff' THEN COALESCE($5, staff_role) ELSE NULL END,
       status = COALESCE($6, status), updated_at = NOW()
     WHERE id = $7 RETURNING *`,
    [data.firstName ?? null, data.lastName ?? null, data.email ?? null, data.phone ?? null, data.staffRole ?? null, data.status ?? null, id],
  );
  if (data.status === 'disabled') {
    // kick the user out of every active session
    await query('UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL', [id]);
  }
  publish('user.updated_by_admin', { actorId, entity: 'user', entityId: id, changes: Object.keys(data) });
  return toPublicUser(rows[0]);
}

export async function createStaff(data, actorId) {
  await assertEmailFree(data.email);
  const { rows } = await query(
    `INSERT INTO users (first_name, last_name, email, phone, password_hash, role, staff_role)
     VALUES ($1,$2,$3,$4,$5,'staff',$6) RETURNING *`,
    [data.firstName, data.lastName, data.email, data.phone, await hashPassword(data.password), data.staffRole],
  );
  const u = rows[0];
  publish('staff.created', {
    actorId, entity: 'user', entityId: u.id, userId: u.id, email: u.email, firstName: u.first_name, staffRole: u.staff_role,
  });
  return toPublicUser(u);
}

export async function deleteStaff(id, actorId) {
  const { rows } = await query(`SELECT 1 FROM users WHERE id = $1 AND role = 'staff'`, [id]);
  if (!rows.length) throw notFound('Staff member not found');
  // unassign open deliveries so they can be re-assigned
  await query(
    `UPDATE deliveries SET staff_id = NULL, delivery_status = 'pending' WHERE staff_id = $1 AND delivery_status = 'assigned'`,
    [id],
  );
  await query('DELETE FROM users WHERE id = $1', [id]);
  publish('staff.deleted', { actorId, entity: 'user', entityId: id });
}
