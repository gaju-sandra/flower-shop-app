import { query, withTransaction } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { invalidate } from '../../utils/cache.js';
import { forbidden, notFound } from '../../utils/http.js';

const toReview = (r) => ({
  id: r.id,
  productId: r.product_id,
  productName: r.product_name,
  userId: r.user_id,
  author: r.first_name ? `${r.first_name} ${r.last_name?.[0] ?? ''}.` : undefined,
  rating: r.rating,
  comment: r.comment,
  status: r.status,
  createdAt: r.created_at,
});

async function refreshProductRating(db, productId) {
  await db.query(
    `UPDATE products p SET
       rating_avg   = COALESCE((SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE product_id = $1 AND status = 'visible'), 0),
       rating_count = (SELECT COUNT(*) FROM reviews WHERE product_id = $1 AND status = 'visible')
     WHERE p.id = $1`,
    [productId],
  );
  invalidate('products:');
}

export async function listReviewsForProduct(productId, limit = 20) {
  const { rows } = await query(
    `SELECT r.*, u.first_name, u.last_name FROM reviews r JOIN users u ON u.id = r.user_id
     WHERE r.product_id = $1 AND r.status = 'visible' ORDER BY r.created_at DESC LIMIT $2`,
    [productId, limit],
  );
  return rows.map(toReview);
}

/** Products the customer received (delivered orders) and whether they reviewed them. */
export async function reviewableProducts(userId) {
  const { rows } = await query(
    `SELECT DISTINCT ON (p.id) p.id, p.name, p.image_url, r.id AS review_id, r.rating
     FROM orders o
     JOIN order_items oi ON oi.order_id = o.id
     JOIN products p ON p.id = oi.product_id
     LEFT JOIN reviews r ON r.product_id = p.id AND r.user_id = o.user_id
     WHERE o.user_id = $1 AND o.status = 'delivered'
     ORDER BY p.id`,
    [userId],
  );
  return rows.map((r) => ({ productId: r.id, name: r.name, imageUrl: r.image_url, reviewed: Boolean(r.review_id), rating: r.rating }));
}

/** Customers may review a flower only after an order containing it was delivered. Re-submitting edits the review. */
export async function upsertReview(userId, { productId, rating, comment }) {
  const { rows: eligible } = await query(
    `SELECT 1 FROM orders o JOIN order_items oi ON oi.order_id = o.id
     WHERE o.user_id = $1 AND oi.product_id = $2 AND o.status = 'delivered' LIMIT 1`,
    [userId, productId],
  );
  if (!eligible.length) throw forbidden('You can review a flower after your order containing it is delivered');
  const review = await withTransaction(async (db) => {
    const { rows } = await db.query(
      `INSERT INTO reviews (user_id, product_id, rating, comment) VALUES ($1,$2,$3,$4)
       ON CONFLICT (user_id, product_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, created_at = NOW()
       RETURNING *`,
      [userId, productId, rating, comment || null],
    );
    await refreshProductRating(db, productId);
    return rows[0];
  });
  publish('review.submitted', { actorId: userId, entity: 'review', entityId: review.id, productId, rating });
  return toReview(review);
}

export async function listAllReviews({ status, rating } = {}) {
  const params = [];
  const where = [];
  if (status) { params.push(status); where.push(`r.status = $${params.length}`); }
  if (rating) { params.push(rating); where.push(`r.rating = $${params.length}`); }
  const { rows } = await query(
    `SELECT r.*, u.first_name, u.last_name, p.name AS product_name
     FROM reviews r JOIN users u ON u.id = r.user_id JOIN products p ON p.id = r.product_id
     ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY r.created_at DESC LIMIT 200`,
    params,
  );
  return rows.map(toReview);
}

export async function setReviewStatus(id, status, actorId) {
  await withTransaction(async (db) => {
    const { rows } = await db.query('UPDATE reviews SET status = $1 WHERE id = $2 RETURNING product_id', [status, id]);
    if (!rows[0]) throw notFound('Review not found');
    await refreshProductRating(db, rows[0].product_id);
  });
  publish('review.moderated', { actorId, entity: 'review', entityId: id, status });
}

export async function deleteReview(id, actorId) {
  await withTransaction(async (db) => {
    const { rows } = await db.query('DELETE FROM reviews WHERE id = $1 RETURNING product_id', [id]);
    if (!rows[0]) throw notFound('Review not found');
    await refreshProductRating(db, rows[0].product_id);
  });
  publish('review.deleted', { actorId, entity: 'review', entityId: id });
}
