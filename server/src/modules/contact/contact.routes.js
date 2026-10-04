import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { env } from '../../config/env.js';
import { publish } from '../../messaging/broker.js';
import { authenticate, authorize, optionalAuth } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { contactRepo } from '../../repositories/documentRepository.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, notFound } from '../../utils/http.js';
import * as common from '../../validation/common.js';

/** Contact messages are documents (MongoDB): free-form, with an embedded reply thread. */
const router = Router();

const contactLimiter = rateLimit({ windowMs: 60 * 60 * 1000, limit: env.isTest ? 1000 : 10, standardHeaders: 'draft-7', legacyHeaders: false });

router.post(
  '/',
  contactLimiter,
  optionalAuth,
  validate(
    z.object({
      name: z.string().trim().min(2, 'Please tell us your name').max(80),
      email: common.email,
      phone: common.phone.optional().or(z.literal('')),
      subject: z.string().trim().min(3, 'Subject is too short').max(120),
      message: z.string().trim().min(10, 'Message should be at least 10 characters').max(2000),
    }),
  ),
  asyncHandler(async (req, res) => {
    const doc = await contactRepo.create({ ...req.body, userId: req.user?.id });
    publish('contact.received', {
      actorId: req.user?.id, entity: 'contact_message', entityId: String(doc._id),
      name: doc.name, email: doc.email, subject: doc.subject,
    });
    res.status(201).json({ message: 'Thank you! Your message has been sent. 💌' });
  }),
);

router.use(authenticate);

router.get(
  '/',
  authorize(P.MESSAGE_READ),
  asyncHandler(async (req, res) => res.json(await contactRepo.list({ status: req.query.status || undefined }))),
);

router.patch(
  '/:id',
  authorize(P.MESSAGE_READ),
  validate(z.object({ status: z.enum(['new', 'read', 'replied', 'closed']) })),
  asyncHandler(async (req, res) => {
    const doc = await contactRepo.update(req.params.id, { status: req.body.status });
    if (!doc) throw notFound('Message not found');
    res.json(doc);
  }),
);

router.post(
  '/:id/reply',
  authorize(P.MESSAGE_REPLY),
  validate(z.object({ body: z.string().trim().min(2).max(2000) })),
  asyncHandler(async (req, res) => {
    const doc = await contactRepo.update(
      req.params.id,
      { status: 'replied' },
      { body: req.body.body, authorId: req.user.id, authorName: req.user.name },
    );
    if (!doc) throw notFound('Message not found');
    publish('contact.replied', {
      actorId: req.user.id, entity: 'contact_message', entityId: req.params.id,
      email: doc.email, name: doc.name, subject: doc.subject, reply: req.body.body, staffName: req.user.name,
    });
    res.json(doc);
  }),
);

export default router;
