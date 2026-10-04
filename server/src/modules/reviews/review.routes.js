import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler } from '../../utils/http.js';
import { idParam } from '../../validation/common.js';
import * as reviews from './review.service.js';

const router = Router();
router.use(authenticate);

router.get(
  '/mine/reviewable',
  authorize(P.REVIEW_CREATE),
  asyncHandler(async (req, res) => res.json(await reviews.reviewableProducts(req.user.id))),
);

router.post(
  '/',
  authorize(P.REVIEW_CREATE),
  validate(
    z.object({
      productId: z.coerce.number().int().positive(),
      rating: z.coerce.number().int().min(1).max(5),
      comment: z.string().trim().max(1000).optional(),
    }),
  ),
  asyncHandler(async (req, res) => res.status(201).json(await reviews.upsertReview(req.user.id, req.body))),
);

router.get(
  '/',
  authorize(P.REVIEW_MODERATE),
  validate(
    z.object({
      status: z.enum(['visible', 'hidden']).optional(),
      rating: z.coerce.number().int().min(1).max(5).optional(),
    }),
    'query',
  ),
  asyncHandler(async (req, res) => res.json(await reviews.listAllReviews(req.query))),
);

router.patch(
  '/:id',
  authorize(P.REVIEW_MODERATE),
  validate(idParam, 'params'),
  validate(z.object({ status: z.enum(['visible', 'hidden']) })),
  asyncHandler(async (req, res) => {
    await reviews.setReviewStatus(req.params.id, req.body.status, req.user.id);
    res.status(204).end();
  }),
);

router.delete(
  '/:id',
  authorize(P.REVIEW_MODERATE),
  validate(idParam, 'params'),
  asyncHandler(async (req, res) => {
    await reviews.deleteReview(req.params.id, req.user.id);
    res.status(204).end();
  }),
);

export default router;
