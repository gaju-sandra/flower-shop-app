import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, paging } from '../../utils/http.js';
import { idParam } from '../../validation/common.js';
import * as payments from './payment.service.js';

const router = Router();
router.use(authenticate);

router.get(
  '/',
  authorize(P.PAYMENT_READ),
  validate(
    z.object({
      status: z.enum(['pending', 'paid', 'failed', 'refunded']).optional(),
      method: z.enum(Object.keys(payments.METHOD_LABELS)).optional(),
      page: z.coerce.number().optional(),
      limit: z.coerce.number().optional(),
    }),
    'query',
  ),
  asyncHandler(async (req, res) => res.json(await payments.listPayments({ ...req.query, ...paging(req.query, 20, 100) }))),
);

router.patch(
  '/:id',
  authorize(P.PAYMENT_MANAGE),
  validate(idParam, 'params'),
  validate(z.object({ status: z.enum(['paid', 'refunded', 'failed']) })),
  asyncHandler(async (req, res) => res.json(await payments.setPaymentStatus(req.params.id, req.body.status, req.user.id))),
);

export default router;
