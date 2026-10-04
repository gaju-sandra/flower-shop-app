import { query } from '../../db/postgres.js';
import { badRequest, notFound } from '../../utils/http.js';
import { computeTotals, unitPrice } from '../orders/pricing.js';
import { getSettings } from '../settings/settings.service.js';

async function cartId(userId) {
  const { rows } = await query(
    `INSERT INTO cart (user_id) VALUES ($1) ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id RETURNING id`,
    [userId],
  );
  return rows[0].id;
}

const toLine = (r) => ({
  productId: r.product_id,
  name: r.name,
  slug: r.slug,
  imageUrl: r.image_url,
  price: r.price,
  discountPercent: r.discount_percent,
  unitPrice: unitPrice(r.price, r.discount_percent),
  quantity: r.quantity,
  stock: r.stock,
  available: r.status === 'active' && r.stock > 0,
  savedForLater: r.saved_for_later,
  lineTotal: unitPrice(r.price, r.discount_percent) * r.quantity,
});

async function summarize(lines) {
  const settings = await getSettings();
  const active = lines.filter((l) => !l.savedForLater && l.available);
  return {
    items: lines.filter((l) => !l.savedForLater),
    saved: lines.filter((l) => l.savedForLater),
    summary: { ...computeTotals({ lines: active, settings: settings.delivery }), freeDeliveryThreshold: settings.delivery.freeDeliveryThreshold },
  };
}

export async function getCart(userId) {
  const id = await cartId(userId);
  const { rows } = await query(
    `SELECT ci.product_id, ci.quantity, ci.saved_for_later, p.name, p.slug, p.image_url, p.price,
            p.discount_percent, p.stock, p.status
     FROM cart_items ci JOIN products p ON p.id = ci.product_id
     WHERE ci.cart_id = $1 ORDER BY ci.id`,
    [id],
  );
  return summarize(rows.map(toLine));
}

/** Price a guest cart held in the browser (no persistence). */
export async function quote(items) {
  if (!items.length) return summarize([]);
  const ids = items.map((i) => i.productId);
  const { rows } = await query(
    'SELECT id AS product_id, name, slug, image_url, price, discount_percent, stock, status FROM products WHERE id = ANY($1)',
    [ids],
  );
  const byId = new Map(rows.map((r) => [r.product_id, r]));
  const lines = items
    .filter((i) => byId.has(i.productId))
    .map((i) => toLine({ ...byId.get(i.productId), quantity: i.quantity, saved_for_later: false }));
  return summarize(lines);
}

async function assertStock(productId, quantity) {
  const { rows } = await query('SELECT name, stock, status FROM products WHERE id = $1', [productId]);
  const p = rows[0];
  if (!p || p.status !== 'active') throw notFound('This flower is no longer available');
  if (quantity > p.stock) throw badRequest(`Only ${p.stock} ${p.name} left in stock`);
}

export async function addItem(userId, productId, quantity) {
  const id = await cartId(userId);
  const { rows } = await query('SELECT quantity FROM cart_items WHERE cart_id = $1 AND product_id = $2', [id, productId]);
  const newQty = Math.min(99, (rows[0]?.quantity || 0) + quantity);
  await assertStock(productId, newQty);
  await query(
    `INSERT INTO cart_items (cart_id, product_id, quantity) VALUES ($1,$2,$3)
     ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = $3, saved_for_later = FALSE`,
    [id, productId, newQty],
  );
  return getCart(userId);
}

export async function updateItem(userId, productId, { quantity, savedForLater }) {
  const id = await cartId(userId);
  if (quantity !== undefined) await assertStock(productId, quantity);
  const { rowCount } = await query(
    `UPDATE cart_items SET quantity = COALESCE($3, quantity), saved_for_later = COALESCE($4, saved_for_later)
     WHERE cart_id = $1 AND product_id = $2`,
    [id, productId, quantity ?? null, savedForLater ?? null],
  );
  if (!rowCount) throw notFound('Item is not in your cart');
  return getCart(userId);
}

export async function removeItem(userId, productId) {
  const id = await cartId(userId);
  await query('DELETE FROM cart_items WHERE cart_id = $1 AND product_id = $2', [id, productId]);
  return getCart(userId);
}

/** Merge a guest (browser) cart into the user's cart after login. */
export async function merge(userId, items) {
  const id = await cartId(userId);
  for (const { productId, quantity } of items) {
    await query(
      `INSERT INTO cart_items (cart_id, product_id, quantity)
       SELECT $1, p.id, LEAST($3, p.stock, 99) FROM products p WHERE p.id = $2 AND p.status = 'active' AND p.stock > 0
       ON CONFLICT (cart_id, product_id)
       DO UPDATE SET quantity = LEAST(cart_items.quantity + EXCLUDED.quantity, 99)`,
      [id, productId, quantity],
    );
  }
  return getCart(userId);
}

export async function clearPurchased(db, userId) {
  await db.query(
    'DELETE FROM cart_items WHERE saved_for_later = FALSE AND cart_id = (SELECT id FROM cart WHERE user_id = $1)',
    [userId],
  );
}
