import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler } from '../../utils/http.js';
import { idParam } from '../../validation/common.js';
import * as settings from './settings.service.js';

const router = Router();

const sections = {
  store: z.object({
    name: z.string().trim().min(2).max(60),
    phone: z.string().trim().min(6).max(30),
    email: z.string().trim().email(),
    address: z.string().trim().min(5).max(200),
    hours: z.string().trim().max(120),
  }),
  delivery: z.object({
    deliveryFee: z.coerce.number().min(0).max(100_000),
    freeDeliveryThreshold: z.coerce.number().min(0).max(10_000_000),
    timeSlots: z.array(z.string().trim().min(3).max(20)).min(1).max(12),
    sameDayCutoffHour: z.coerce.number().int().min(0).max(23),
  }),
  social: z.object({
    instagram: z.string().url().or(z.literal('')),
    facebook: z.string().url().or(z.literal('')),
    x: z.string().url().or(z.literal('')),
    whatsapp: z.string().url().or(z.literal('')),
  }),
};

// Public storefront config (contact info, delivery fee, time slots, gift options).
router.get(
  '/public',
  asyncHandler(async (_req, res) => {
    const [all, gifts] = await Promise.all([settings.getSettings(), settings.listGiftOptions()]);
    res.set('Cache-Control', 'public, max-age=60');
    res.json({ ...all, giftOptions: gifts });
  }),
);

router.use(authenticate, authorize(P.SETTINGS_MANAGE));

router.get(
  '/',
  asyncHandler(async (_req, res) => {
    const [all, gifts] = await Promise.all([settings.getSettings(), settings.listGiftOptions(true)]);
    res.json({ ...all, giftOptions: gifts });
  }),
);

router.put(
  '/:section',
  asyncHandler(async (req, res) => {
    const schema = sections[req.params.section];
    if (!schema) return res.status(404).json({ message: 'Unknown settings section' });
    res.json(await settings.updateSettings(req.params.section, schema.parse(req.body)));
  }),
);

router.patch(
  '/gift-options/:id',
  validate(idParam, 'params'),
  validate(z.object({ price: z.coerce.number().min(0).max(1_000_000).optional(), active: z.boolean().optional() })),
  asyncHandler(async (req, res) => {
    await settings.updateGiftOption(req.params.id, req.body);
    res.json(await settings.listGiftOptions(true));
  }),
);

export default router;
