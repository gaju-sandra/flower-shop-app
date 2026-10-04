import { Router } from 'express';
import { z } from 'zod';
import { query } from '../../db/postgres.js';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler } from '../../utils/http.js';
import { toProduct } from '../products/product.service.js';

const router = Router();
router.use(authenticate, authorize(P.WISHLIST_MANAGE));

const productParam = z.object({ productId: z.coerce.number().int().positive() });

async function list(userId) {
  const { rows } = await query(
    `SELECT p.*, c.name AS category_name, c.slug AS category_slug
     FROM wishlist w JOIN products p ON p.id = w.product_id LEFT JOIN categories c ON c.id = p.category_id
     WHERE w.user_id = $1 ORDER BY w.created_at DESC`,
    [userId],
  );
  return rows.map(toProduct);
}

router.get('/', asyncHandler(async (req, res) => res.json(await list(req.user.id))));

router.post(
  '/:productId',
  validate(productParam, 'params'),
  asyncHandler(async (req, res) => {
    await query('INSERT INTO wishlist (user_id, product_id) VALUES ($1,$2) ON CONFLICT DO NOTHING', [
      req.user.id,
      req.params.productId,
    ]);
    res.status(201).json(await list(req.user.id));
  }),
);

router.delete(
  '/:productId',
  validate(productParam, 'params'),
  asyncHandler(async (req, res) => {
    await query('DELETE FROM wishlist WHERE user_id = $1 AND product_id = $2', [req.user.id, req.params.productId]);
    res.json(await list(req.user.id));
  }),
);

export default router;
