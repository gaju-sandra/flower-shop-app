import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, paging } from '../../utils/http.js';
import * as common from '../../validation/common.js';
import * as orders from './order.service.js';

const router = Router();
router.use(authenticate);

const MESSAGE_TYPES = ['birthday', 'romantic', 'congratulations', 'anniversary', 'wedding', 'thank_you', 'custom'];

const checkoutSchema = z.object({
  contact: z.object({ fullName: z.string().trim().min(3, 'Full name is required').max(120), phone: common.phone, email: common.email }),
  delivery: common.address.extend({
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a delivery date'),
    time: z.string().min(3, 'Choose a delivery time'),
  }),
  instructions: z.string().trim().max(500).optional(),
  message: z
    .object({
      recipientName: z.string().trim().max(120).optional(),
      type: z.enum(MESSAGE_TYPES).optional(),
      text: z.string().trim().max(500).optional(),
    })
    .optional(),
  giftOptions: z.array(z.string().max(30)).max(10).default([]),
  promoCode: z.string().trim().max(30).optional().or(z.literal('')),
  saveAddress: z.boolean().optional(),
  payment: z.object({
    method: z.enum(['mtn_momo', 'airtel_money', 'card', 'cash_on_delivery']),
    phone: z.string().optional(),
    cardName: z.string().max(80).optional(),
    cardNumber: z.string().max(23).optional(),
    expiry: z.string().max(7).optional(),
    cvc: z.string().max(4).optional(),
  }),
});

const listQuery = z.object({
  status: z.enum([...Object.keys(orders.ORDER_FLOW), 'active']).optional(),
  search: z.string().trim().max(60).optional(),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  page: z.coerce.number().optional(),
  limit: z.coerce.number().optional(),
});

// ---------- customer ----------
router.post(
  '/quote',
  authorize(P.ORDER_CREATE),
  validate(z.object({ giftOptions: z.array(z.string()).max(10).default([]), promoCode: z.string().trim().max(30).optional() })),
  asyncHandler(async (req, res) => res.json(await orders.quoteCheckout(req.user.id, req.body))),
);

router.post(
  '/',
  authorize(P.ORDER_CREATE),
  validate(checkoutSchema),
  asyncHandler(async (req, res) => res.status(201).json(await orders.createOrder(req.user.id, req.body))),
);

router.get(
  '/mine',
  authorize(P.ORDER_READ_OWN),
  validate(listQuery, 'query'),
  asyncHandler(async (req, res) =>
    res.json(await orders.listOrders({ ...req.query, userId: req.user.id, ...paging(req.query, 10, 50) })),
  ),
);

router.post(
  '/:id/cancel',
  authorize(P.ORDER_READ_OWN),
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => res.json(await orders.cancelOwnOrder(req.params.id, req.user))),
);

// ---------- staff / admin ----------
router.get(
  '/',
  authorize(P.ORDER_READ_ANY),
  validate(listQuery, 'query'),
  asyncHandler(async (req, res) => res.json(await orders.listOrders({ ...req.query, ...paging(req.query, 15, 100) }))),
);

router.patch(
  '/:id/status',
  authorize(P.ORDER_UPDATE_STATUS),
  validate(common.idParam, 'params'),
  validate(z.object({ status: z.enum(Object.keys(orders.ORDER_FLOW)), note: z.string().trim().max(300).optional() })),
  asyncHandler(async (req, res) =>
    res.json(await orders.updateOrderStatus(req.params.id, req.body.status, req.user, req.body.note)),
  ),
);

// shared: owner or staff/admin (object-level check inside the service)
router.get(
  '/:id',
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => res.json(await orders.getOrder(req.params.id, req.user))),
);

export default router;
