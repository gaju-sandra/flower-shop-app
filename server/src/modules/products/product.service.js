import { query } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { cached, invalidate } from '../../utils/cache.js';
import { conflict, notFound, slugify } from '../../utils/http.js';

export const finalPrice = (price, discount) => Math.round(Number(price) * (1 - (discount || 0) / 100));

export function toProduct(r) {
  return {
    id: r.id,
    name: r.name,
    slug: r.slug,
    description: r.description,
    categoryId: r.category_id,
    category: r.category_name ?? null,
    categorySlug: r.category_slug ?? null,
    occasions: r.occasions,
    price: r.price,
    discountPercent: r.discount_percent,
    finalPrice: finalPrice(r.price, r.discount_percent),
    stock: r.stock,
    imageUrl: r.image_url,
    status: r.status,
    soldCount: r.sold_count,
    rating: Number(r.rating_avg),
    ratingCount: r.rating_count,
    createdAt: r.created_at,
  };
}

const EFFECTIVE_PRICE = 'ROUND(p.price * (100 - p.discount_percent) / 100)';
const SORTS = {
  newest: 'p.created_at DESC',
  popular: 'p.sold_count DESC, p.rating_avg DESC',
  rating: 'p.rating_avg DESC, p.rating_count DESC',
  price_asc: `${EFFECTIVE_PRICE} ASC`,
  price_desc: `${EFFECTIVE_PRICE} DESC`,
  name: 'p.name ASC',
};

const BASE_SELECT = `
  SELECT p.*, c.name AS category_name, c.slug AS category_slug
  FROM products p LEFT JOIN categories c ON c.id = p.category_id`;

export async function listProducts(f) {
  const where = [];
  const params = [];
  const add = (sql, value) => {
    params.push(value);
    where.push(sql.replace('?', `$${params.length}`));
  };
  if (!f.includeInactive) where.push(`p.status = 'active'`);
  if (f.search) {
    params.push(`%${f.search}%`);
    where.push(`(p.name ILIKE $${params.length} OR p.description ILIKE $${params.length})`);
  }
  if (f.category) add('c.slug = ?', f.category);
  if (f.occasion) add('? = ANY(p.occasions)', f.occasion);
  if (f.minPrice != null) add(`${EFFECTIVE_PRICE} >= ?`, f.minPrice);
  if (f.maxPrice != null) add(`${EFFECTIVE_PRICE} <= ?`, f.maxPrice);
  if (f.onSale) where.push('p.discount_percent > 0');
  if (f.lowStock) where.push('p.stock <= 5');

  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const order = SORTS[f.sort] || SORTS.popular;
  const key = `products:${JSON.stringify(f)}`;

  return cached(key, f.includeInactive ? 0 : 30_000, async () => {
    const [{ rows }, { rows: countRows }] = await Promise.all([
      query(`${BASE_SELECT} ${whereSql} ORDER BY ${order}, p.id LIMIT ${f.limit} OFFSET ${f.offset}`, params),
      query(`SELECT COUNT(*) AS total FROM products p LEFT JOIN categories c ON c.id = p.category_id ${whereSql}`, params),
    ]);
    const total = countRows[0].total;
    return { items: rows.map(toProduct), total, page: f.page, pages: Math.max(1, Math.ceil(total / f.limit)) };
  });
}

export async function getProduct(idOrSlug, { includeInactive = false } = {}) {
  const byId = /^\d+$/.test(String(idOrSlug));
  const { rows } = await query(
    `${BASE_SELECT} WHERE ${byId ? 'p.id = $1' : 'p.slug = $1'} ${includeInactive ? '' : `AND p.status = 'active'`}`,
    [idOrSlug],
  );
  if (!rows[0]) throw notFound('Flower not found');
  return toProduct(rows[0]);
}

export async function relatedProducts(product, limit = 4) {
  const { rows } = await query(
    `${BASE_SELECT} WHERE p.status = 'active' AND p.id <> $1 AND (p.category_id = $2 OR p.occasions && $3)
     ORDER BY p.sold_count DESC LIMIT $4`,
    [product.id, product.categoryId, product.occasions, limit],
  );
  return rows.map(toProduct);
}

async function uniqueSlug(name, excludeId = 0) {
  const base = slugify(name) || 'flower';
  let slug = base;
  for (let i = 2; ; i += 1) {
    const { rows } = await query('SELECT 1 FROM products WHERE slug = $1 AND id <> $2', [slug, excludeId]);
    if (!rows.length) return slug;
    slug = `${base}-${i}`;
  }
}

export async function createProduct(d, actorId) {
  const slug = await uniqueSlug(d.name);
  const { rows } = await query(
    `INSERT INTO products (name, slug, description, category_id, occasions, price, discount_percent, stock, image_url, status)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id`,
    [d.name, slug, d.description, d.categoryId, d.occasions, d.price, d.discountPercent, d.stock, d.imageUrl, d.status],
  );
  invalidate('products:');
  publish('product.created', { actorId, entity: 'product', entityId: rows[0].id, name: d.name });
  return getProduct(rows[0].id, { includeInactive: true });
}

const COLUMN_MAP = {
  name: 'name', description: 'description', categoryId: 'category_id', occasions: 'occasions',
  price: 'price', discountPercent: 'discount_percent', stock: 'stock', imageUrl: 'image_url', status: 'status',
};

export async function updateProduct(id, d, actorId) {
  const sets = [];
  const params = [];
  for (const [k, col] of Object.entries(COLUMN_MAP)) {
    if (d[k] !== undefined) {
      params.push(d[k]);
      sets.push(`${col} = $${params.length}`);
    }
  }
  if (d.name) {
    params.push(await uniqueSlug(d.name, id));
    sets.push(`slug = $${params.length}`);
  }
  if (!sets.length) return getProduct(id, { includeInactive: true });
  params.push(id);
  const { rowCount } = await query(
    `UPDATE products SET ${sets.join(', ')}, updated_at = NOW() WHERE id = $${params.length}`,
    params,
  );
  if (!rowCount) throw notFound('Flower not found');
  invalidate('products:');
  publish('product.updated', { actorId, entity: 'product', entityId: id, changes: Object.keys(d) });
  return getProduct(id, { includeInactive: true });
}

export async function deleteProduct(id, actorId) {
  const { rowCount } = await query('DELETE FROM products WHERE id = $1', [id]);
  if (!rowCount) throw notFound('Flower not found');
  invalidate('products:');
  publish('product.deleted', { actorId, entity: 'product', entityId: id });
}

// ---------- categories ----------
export const listCategories = () =>
  cached('products:categories', 60_000, async () => {
    const { rows } = await query(
      `SELECT c.*, COUNT(p.id) FILTER (WHERE p.status = 'active') AS product_count
       FROM categories c LEFT JOIN products p ON p.category_id = c.id
       GROUP BY c.id ORDER BY c.name`,
    );
    return rows.map((c) => ({ id: c.id, name: c.name, slug: c.slug, description: c.description, productCount: c.product_count }));
  });

export async function createCategory({ name, description }) {
  const { rows } = await query(
    'INSERT INTO categories (name, slug, description) VALUES ($1,$2,$3) RETURNING *',
    [name, slugify(name), description || null],
  );
  invalidate('products:');
  return rows[0];
}

export async function updateCategory(id, { name, description }) {
  const { rows } = await query(
    'UPDATE categories SET name = $1, slug = $2, description = $3 WHERE id = $4 RETURNING *',
    [name, slugify(name), description || null, id],
  );
  if (!rows[0]) throw notFound('Category not found');
  invalidate('products:');
  return rows[0];
}

export async function deleteCategory(id) {
  const { rows } = await query('SELECT COUNT(*) AS n FROM products WHERE category_id = $1', [id]);
  if (rows[0].n > 0) throw conflict('Move or delete the flowers in this category first');
  await query('DELETE FROM categories WHERE id = $1', [id]);
  invalidate('products:');
}
