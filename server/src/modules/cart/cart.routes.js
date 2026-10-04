import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler } from '../../utils/http.js';
import * as cart from './cart.service.js';

const router = Router();

const lineSchema = z.object({
  productId: z.coerce.number().int().positive(),
  quantity: z.coerce.number().int().min(1).max(99).default(1),
});
const productParam = z.object({ productId: z.coerce.number().int().positive() });

// Guest carts live in the browser; this endpoint prices them with live data.
router.post(
  '/quote',
  validate(z.object({ items: z.array(lineSchema).max(50) })),
  asyncHandler(async (req, res) => res.json(await cart.quote(req.body.items))),
);

router.use(authenticate, authorize(P.CART_MANAGE));

router.get('/', asyncHandler(async (req, res) => res.json(await cart.getCart(req.user.id))));

router.post(
  '/items',
  validate(lineSchema),
  asyncHandler(async (req, res) => res.json(await cart.addItem(req.user.id, req.body.productId, req.body.quantity))),
);

router.patch(
  '/items/:productId',
  validate(productParam, 'params'),
  validate(
    z
      .object({ quantity: z.coerce.number().int().min(1).max(99).optional(), savedForLater: z.boolean().optional() })
      .refine((d) => d.quantity !== undefined || d.savedForLater !== undefined, 'Nothing to update'),
  ),
  asyncHandler(async (req, res) => res.json(await cart.updateItem(req.user.id, req.params.productId, req.body))),
);

router.delete(
  '/items/:productId',
  validate(productParam, 'params'),
  asyncHandler(async (req, res) => res.json(await cart.removeItem(req.user.id, req.params.productId))),
);

router.post(
  '/merge',
  validate(z.object({ items: z.array(lineSchema).max(50) })),
  asyncHandler(async (req, res) => res.json(await cart.merge(req.user.id, req.body.items))),
);

export default router;
