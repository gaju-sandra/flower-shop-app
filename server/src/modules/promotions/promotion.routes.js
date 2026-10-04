import { Router } from 'express';
import { z } from 'zod';
import { query } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, notFound } from '../../utils/http.js';
import { idParam, optionalFk } from '../../validation/common.js';

const router = Router();

const toPromo = (p) => ({
  id: p.id, code: p.code, title: p.title, description: p.description, discountPercent: p.discount_percent,
  minOrder: p.min_order, categoryId: p.category_id, category: p.category_name, startsAt: p.starts_at,
  endsAt: p.ends_at, active: p.active, timesUsed: p.times_used,
});

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const promoBody = z
  .object({
    code: z.string().trim().toUpperCase().regex(/^[A-Z0-9_-]{3,30}$/, 'Code: 3-30 letters, numbers, - or _'),
    title: z.string().trim().min(3).max(120),
    description: z.string().trim().max(300).optional(),
    discountPercent: z.coerce.number().int().min(1).max(90),
    minOrder: z.coerce.number().min(0).default(0),
    categoryId: optionalFk,
    startsAt: date,
    endsAt: date.nullable().optional().or(z.literal('').transform(() => null)),
    active: z.boolean().default(true),
  })
  .refine((d) => !d.endsAt || d.endsAt >= d.startsAt, { message: 'End date must be after the start date', path: ['endsAt'] });

// Public: currently running offers (for the "Special Offers" banners).
router.get(
  '/active',
  asyncHandler(async (_req, res) => {
    const { rows } = await query(
      `SELECT p.*, c.name AS category_name FROM promotions p LEFT JOIN categories c ON c.id = p.category_id
       WHERE p.active AND p.starts_at <= CURRENT_DATE AND (p.ends_at IS NULL OR p.ends_at >= CURRENT_DATE)
       ORDER BY p.discount_percent DESC`,
    );
    res.json(rows.map(toPromo));
  }),
);

router.use(authenticate, authorize(P.PROMOTION_MANAGE));

router.get(
  '/',
  asyncHandler(async (_req, res) => {
    const { rows } = await query(
      `SELECT p.*, c.name AS category_name, (SELECT COUNT(*) FROM orders o WHERE o.promotion_id = p.id) AS times_used
       FROM promotions p LEFT JOIN categories c ON c.id = p.category_id ORDER BY p.created_at DESC`,
    );
    res.json(rows.map(toPromo));
  }),
);

router.post(
  '/',
  validate(promoBody),
  asyncHandler(async (req, res) => {
    const d = req.body;
    const { rows } = await query(
      `INSERT INTO promotions (code, title, description, discount_percent, min_order, category_id, starts_at, ends_at, active)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING *`,
      [d.code, d.title, d.description || null, d.discountPercent, d.minOrder, d.categoryId ?? null, d.startsAt, d.endsAt ?? null, d.active],
    );
    publish('promotion.created', { actorId: req.user.id, entity: 'promotion', entityId: rows[0].id, code: d.code });
    res.status(201).json(toPromo(rows[0]));
  }),
);

router.put(
  '/:id',
  validate(idParam, 'params'),
  validate(promoBody),
  asyncHandler(async (req, res) => {
    const d = req.body;
    const { rows } = await query(
      `UPDATE promotions SET code=$1, title=$2, description=$3, discount_percent=$4, min_order=$5, category_id=$6,
         starts_at=$7, ends_at=$8, active=$9 WHERE id=$10 RETURNING *`,
      [d.code, d.title, d.description || null, d.discountPercent, d.minOrder, d.categoryId ?? null, d.startsAt, d.endsAt ?? null, d.active, req.params.id],
    );
    if (!rows[0]) throw notFound('Promotion not found');
    res.json(toPromo(rows[0]));
  }),
);

router.delete(
  '/:id',
  validate(idParam, 'params'),
  asyncHandler(async (req, res) => {
    await query('DELETE FROM promotions WHERE id = $1', [req.params.id]);
    res.status(204).end();
  }),
);

export default router;
