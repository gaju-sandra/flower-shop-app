import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, paging } from '../../utils/http.js';
import { boolish, idParam } from '../../validation/common.js';
import * as deliveries from './delivery.service.js';

const router = Router();
router.use(authenticate);

router.get(
  '/',
  authorize(P.DELIVERY_READ),
  validate(
    z.object({
      status: z.enum(Object.keys(deliveries.DELIVERY_FLOW)).optional(),
      date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
      mine: boolish.optional(),
      page: z.coerce.number().optional(),
      limit: z.coerce.number().optional(),
    }),
    'query',
  ),
  asyncHandler(async (req, res) =>
    res.json(
      await deliveries.listDeliveries({
        ...req.query,
        staffId: req.query.mine ? req.user.id : undefined,
        ...paging(req.query, 20, 100),
      }),
    ),
  ),
);

router.get('/couriers', authorize(P.DELIVERY_MANAGE), asyncHandler(async (_req, res) => res.json(await deliveries.listCouriers())));

router.patch(
  '/:id/assign',
  authorize(P.DELIVERY_MANAGE),
  validate(idParam, 'params'),
  validate(z.object({ staffId: z.coerce.number().int().positive() })),
  asyncHandler(async (req, res) => res.json(await deliveries.assignDelivery(req.params.id, req.body.staffId, req.user.id))),
);

router.patch(
  '/:id/status',
  authorize(P.DELIVERY_MANAGE),
  validate(idParam, 'params'),
  validate(
    z.object({
      status: z.enum(['picked_up', 'on_the_way', 'delivered']),
      notes: z.string().trim().max(300).optional(),
    }),
  ),
  asyncHandler(async (req, res) =>
    res.json(await deliveries.updateDeliveryStatus(req.params.id, req.body.status, req.user, req.body.notes)),
  ),
);

export default router;
