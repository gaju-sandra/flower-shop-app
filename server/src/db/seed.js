/**
 * Seeds demo data. Safe by default: does nothing if users already exist.
 *   npm run seed            -> seed an empty database
 *   npm run seed -- --reset -> wipe all tables and re-seed
 *
 * Demo accounts (local development only):
 *   admin@bloomandco.rw          Admin@123
 *   aline.staff@bloomandco.rw    Staff@123   (and the other staff below)
 *   melissa@example.com          Customer@123 (and the other customers below)
 */
import { fileURLToPath } from 'node:url';
import bcrypt from 'bcryptjs';
import { slugify } from '../utils/http.js';
import { formatOrderNumber } from '../modules/orders/order.service.js';
import { unitPrice } from '../modules/orders/pricing.js';
import { migrate } from './migrate.js';
import { pool, withTransaction } from './postgres.js';
import { CATEGORIES, CUSTOMERS, GIFT_OPTIONS, PRODUCTS, PROMOTIONS, REVIEW_TEXTS, STAFF } from './seedData.js';

// deterministic PRNG so every seed produces the same demo data
function mulberry32(a) {
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20261004);
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
const int = (min, max) => min + Math.floor(rand() * (max - min + 1));
const day = (d) => d.toISOString().slice(0, 10);

const SLOTS = ['08:00 - 10:00', '10:00 - 12:00', '12:00 - 14:00', '14:00 - 16:00', '16:00 - 18:00', '18:00 - 20:00'];
const METHODS = ['mtn_momo', 'mtn_momo', 'airtel_money', 'card', 'cash_on_delivery'];
const MESSAGES = [
  ['birthday', 'Happy Birthday! May your day be as beautiful as these flowers. ❤️'],
  ['romantic', 'Just because I love you. 🌹'],
  ['congratulations', 'Congratulations on this amazing achievement!'],
  ['anniversary', 'Happy anniversary, my love. Here is to many more years.'],
  ['thank_you', 'Thank you for everything you do.'],
];

export async function seed({ reset = false, log = console.log } = {}) {
  await migrate({ log });
  const { rows } = await pool.query('SELECT COUNT(*) AS n FROM users');
  if (rows[0].n > 0 && !reset) {
    log('Database already has data - skipping seed (use --reset to wipe and re-seed).');
    return;
  }

  await withTransaction(async (db) => {
    if (reset) {
      await db.query(`TRUNCATE users, categories, products, promotions, gift_options, settings, orders, payments,
        deliveries, reviews, wishlist, cart, cart_items, addresses, refresh_tokens, password_resets,
        order_items, order_status_history RESTART IDENTITY CASCADE`);
    }
    const [adminHash, staffHash, customerHash] = await Promise.all(
      ['Admin@123', 'Staff@123', 'Customer@123'].map((p) => bcrypt.hash(p, 10)),
    );

    // ---- users ----
    await db.query(
      `INSERT INTO users (first_name, last_name, email, phone, password_hash, role) VALUES
       ('Sandra', 'Gaju', 'admin@bloomandco.rw', '0788000100', $1, 'admin')`,
      [adminHash],
    );
    const staffIds = [];
    for (const [first, last, email, phone, staffRole] of STAFF) {
      const r = await db.query(
        `INSERT INTO users (first_name, last_name, email, phone, password_hash, role, staff_role)
         VALUES ($1,$2,$3,$4,$5,'staff',$6) RETURNING id`,
        [first, last, email, phone, staffHash, staffRole],
      );
      staffIds.push({ id: r.rows[0].id, role: staffRole });
    }
    const courierId = staffIds.find((s) => s.role === 'delivery_staff').id;
    const managerId = staffIds.find((s) => s.role === 'order_manager').id;

    const customers = [];
    for (const [i, [first, last, email, phone, addr]] of CUSTOMERS.entries()) {
      const createdAt = new Date(Date.now() - (360 - i * 20) * 86_400_000);
      const r = await db.query(
        `INSERT INTO users (first_name, last_name, email, phone, password_hash, role, created_at)
         VALUES ($1,$2,$3,$4,$5,'customer',$6) RETURNING id`,
        [first, last, email, phone, customerHash, createdAt],
      );
      const id = r.rows[0].id;
      await db.query('INSERT INTO cart (user_id) VALUES ($1)', [id]);
      await db.query(
        `INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, is_default)
         VALUES ($1,'Home',$2,$3,$4,$5,$6,$7,TRUE)`,
        [id, `${first} ${last}`, phone, ...addr],
      );
      customers.push({ id, name: `${first} ${last}`, email, phone, addr });
    }

    // ---- catalogue ----
    const categoryIds = {};
    for (const [name, description] of CATEGORIES) {
      const r = await db.query('INSERT INTO categories (name, slug, description) VALUES ($1,$2,$3) RETURNING id', [
        name, slugify(name), description,
      ]);
      categoryIds[name] = r.rows[0].id;
    }
    const products = [];
    for (const [i, p] of PRODUCTS.entries()) {
      const created = new Date(Date.now() - (PRODUCTS.length - i) * 9 * 86_400_000);
      const r = await db.query(
        `INSERT INTO products (name, slug, description, category_id, occasions, price, discount_percent, stock, image_url, sold_count, created_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *`,
        [p.name, slugify(p.name), p.description, categoryIds[p.category], p.occasions, p.price, p.discount, p.stock, p.imageUrl, p.sold, created],
      );
      products.push(r.rows[0]);
    }
    for (const [code, name, icon, price] of GIFT_OPTIONS) {
      await db.query('INSERT INTO gift_options (code, name, icon, price) VALUES ($1,$2,$3,$4)', [code, name, icon, price]);
    }
    for (const [code, title, description, pct, min] of PROMOTIONS) {
      await db.query(
        `INSERT INTO promotions (code, title, description, discount_percent, min_order, starts_at, ends_at)
         VALUES ($1,$2,$3,$4,$5, CURRENT_DATE - 30, CURRENT_DATE + 120)`,
        [code, title, description, pct, min],
      );
    }

    // ---- order history (12 months, busier around February) ----
    const now = Date.now();
    const delivered = new Map(); // customerId -> Set(productId)
    for (let n = 0; n < 110; n += 1) {
      const daysAgo = n < 12 ? int(0, 4) : Math.floor(Math.pow(rand(), 1.4) * 360);
      const created = new Date(now - daysAgo * 86_400_000 - int(1, 10) * 3600_000);
      if (created.getMonth() !== 1 && rand() < 0.15) continue; // thin out non-Feb months
      const customer = pick(customers);
      const lines = Array.from({ length: int(1, 3) }, () => ({ p: pick(products), qty: int(1, 2) }))
        .filter((l, i, arr) => arr.findIndex((x) => x.p.id === l.p.id) === i);
      const subtotal = lines.reduce((s, l) => s + unitPrice(l.p.price, l.p.discount_percent) * l.qty, 0);
      const deliveryFee = subtotal >= 50000 ? 0 : 2000;
      const gifts = rand() < 0.35 ? [{ code: 'greeting_card', name: 'Greeting card', icon: '💌', price: 1500 }] : [];
      const giftTotal = gifts.reduce((s, g) => s + g.price, 0);
      const total = subtotal + deliveryFee + giftTotal;
      const deliveryDate = new Date(created.getTime() + int(0, 2) * 86_400_000);
      const method = pick(METHODS);

      let status;
      if (daysAgo > 4) status = rand() < 0.92 ? 'delivered' : 'cancelled';
      else status = pick(['pending', 'pending', 'confirmed', 'preparing', 'ready', 'out_for_delivery', 'delivered']);
      const paid = status === 'delivered' || method !== 'cash_on_delivery';
      const paymentStatus = status === 'cancelled' ? (method === 'cash_on_delivery' ? 'failed' : 'refunded') : paid ? 'paid' : 'pending';
      const [msgType, msgText] = rand() < 0.6 ? pick(MESSAGES) : [null, null];
      const [province, district, sector, street] = customer.addr;
      const address = `${street}, ${sector}, ${district}, ${province}`;

      const o = await db.query(
        `INSERT INTO orders (user_id, subtotal, discount, gift_total, delivery_fee, total_amount, customer_name, email, phone,
           province, district, sector, street, delivery_address, delivery_date, delivery_time, recipient_name, message_type,
           gift_message, gift_options, payment_method, status, payment_status, created_at, updated_at)
         VALUES ($1,$2,0,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$23) RETURNING id`,
        [customer.id, subtotal, giftTotal, deliveryFee, total, customer.name, customer.email, customer.phone,
          province, district, sector, street, address, day(deliveryDate), pick(SLOTS),
          msgType ? pick(['Sarah', 'Mama', 'Alice', 'Chris', 'Nadine']) : null, msgType, msgText,
          JSON.stringify(gifts), method, status, paymentStatus, created],
      );
      const orderId = o.rows[0].id;
      await db.query('UPDATE orders SET order_number = $1 WHERE id = $2', [formatOrderNumber(orderId, created), orderId]);
      for (const l of lines) {
        await db.query(
          'INSERT INTO order_items (order_id, product_id, product_name, image_url, quantity, price) VALUES ($1,$2,$3,$4,$5,$6)',
          [orderId, l.p.id, l.p.name, l.p.image_url, l.qty, unitPrice(l.p.price, l.p.discount_percent)],
        );
      }
      const flow = ['pending', 'confirmed', 'preparing', 'ready', 'out_for_delivery', 'delivered'];
      const reached = status === 'cancelled' ? ['pending', 'cancelled'] : flow.slice(0, flow.indexOf(status) + 1);
      for (const [k, s] of reached.entries()) {
        await db.query('INSERT INTO order_status_history (order_id, status, changed_by, created_at) VALUES ($1,$2,$3,$4)', [
          orderId, s, k === 0 ? customer.id : managerId, new Date(created.getTime() + k * 2 * 3600_000),
        ]);
      }
      const deliveryStatus = {
        pending: 'pending', confirmed: 'pending', preparing: 'assigned', ready: 'assigned',
        out_for_delivery: 'on_the_way', delivered: 'delivered', cancelled: 'pending',
      }[status];
      const deliveredAt = status === 'delivered'
        ? new Date(deliveryDate.getTime() + (rand() < 0.9 ? 0 : 86_400_000) + 10 * 3600_000)
        : null;
      await db.query(
        `INSERT INTO deliveries (order_id, staff_id, delivery_address, delivery_date, delivery_time, delivery_status, delivered_at)
         VALUES ($1,$2,$3,$4,(SELECT delivery_time FROM orders WHERE id = $1),$5,$6)`,
        [orderId, deliveryStatus === 'pending' ? null : courierId, address, day(deliveryDate), deliveryStatus, deliveredAt],
      );
      const prefix = { mtn_momo: 'MOMO', airtel_money: 'AIRTEL', card: 'CARD', cash_on_delivery: 'COD' }[method];
      await db.query(
        `INSERT INTO payments (order_id, payment_method, amount, transaction_reference, payer_phone, card_last4, payment_status, payment_date)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
        [orderId, method, total, `${prefix}-SEED${String(orderId).padStart(5, '0')}`,
          method.includes('money') || method === 'mtn_momo' ? customer.phone : null,
          method === 'card' ? '4242' : null, paymentStatus, created],
      );
      if (status === 'delivered') {
        if (!delivered.has(customer.id)) delivered.set(customer.id, new Set());
        lines.forEach((l) => delivered.get(customer.id).add(l.p.id));
      }
    }

    // ---- reviews for delivered flowers ----
    for (const [userId, productIds] of delivered) {
      for (const productId of [...productIds].slice(0, 4)) {
        const [rating, comment] = pick(REVIEW_TEXTS);
        await db.query('INSERT INTO reviews (user_id, product_id, rating, comment) VALUES ($1,$2,$3,$4)', [userId, productId, rating, comment]);
      }
    }
    await db.query(
      `UPDATE products p SET rating_avg = s.avg, rating_count = s.n
       FROM (SELECT product_id, ROUND(AVG(rating)::numeric, 2) AS avg, COUNT(*) AS n FROM reviews GROUP BY product_id) s
       WHERE s.product_id = p.id`,
    );

    // a little wishlist / cart activity for the demo customer
    const melissa = customers[0].id;
    for (const p of products.slice(5, 8)) await db.query('INSERT INTO wishlist (user_id, product_id) VALUES ($1,$2)', [melissa, p.id]);
  });
  log('🌸 Seed complete. Demo logins: admin@bloomandco.rw / Admin@123 · aline.staff@bloomandco.rw / Staff@123 · melissa@example.com / Customer@123');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  seed({ reset: process.argv.includes('--reset') })
    .catch((err) => {
      console.error(err);
      process.exitCode = 1;
    })
    .finally(() => pool.end());
}
