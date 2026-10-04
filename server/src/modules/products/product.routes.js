import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { imageUpload, publicUploadUrl } from '../../middleware/upload.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, paging } from '../../utils/http.js';
import { boolish, idParam, optionalFk } from '../../validation/common.js';
import * as products from './product.service.js';
import { listReviewsForProduct } from '../reviews/review.service.js';

const router = Router();

const listQuery = z.object({
  search: z.string().trim().max(80).optional(),
  category: z.string().trim().max(80).optional(),
  occasion: z.string().trim().max(40).optional(),
  minPrice: z.coerce.number().min(0).optional(),
  maxPrice: z.coerce.number().min(0).optional(),
  sort: z.enum(['newest', 'popular', 'rating', 'price_asc', 'price_desc', 'name']).optional(),
  onSale: boolish.optional(),
  page: z.coerce.number().optional(),
  limit: z.coerce.number().optional(),
});

// Multipart forms send everything as strings, so coerce numbers / arrays.
const csv = z.preprocess(
  (v) => (Array.isArray(v) ? v : typeof v === 'string' ? v.split(',').map((s) => s.trim()).filter(Boolean) : v),
  z.array(z.string().max(40)).max(12),
);
const productBody = z.object({
  name: z.string().trim().min(2).max(120),
  description: z.string().trim().max(2000).default(''),
  categoryId: optionalFk,
  occasions: csv.default([]),
  price: z.coerce.number().min(0).max(10_000_000),
  discountPercent: z.coerce.number().int().min(0).max(90).default(0),
  stock: z.coerce.number().int().min(0).max(100_000),
  imageUrl: z.string().trim().max(500).optional().or(z.literal('')),
  status: z.enum(['active', 'inactive']).default('active'),
});

// ---------- public catalogue ----------
router.get(
  '/',
  validate(listQuery, 'query'),
  asyncHandler(async (req, res) => {
    res.set('Cache-Control', 'public, max-age=30');
    res.json(await products.listProducts({ ...req.query, ...paging(req.query, 12, 48) }));
  }),
);

router.get('/categories', asyncHandler(async (_req, res) => res.json(await products.listCategories())));

router.get(
  '/:idOrSlug',
  asyncHandler(async (req, res) => {
    const product = await products.getProduct(req.params.idOrSlug);
    const [related, reviews] = await Promise.all([
      products.relatedProducts(product),
      listReviewsForProduct(product.id),
    ]);
    res.json({ product, related, reviews });
  }),
);

// ---------- back office ----------
router.get(
  '/admin/all',
  authenticate,
  authorize(P.PRODUCT_READ_ADMIN),
  asyncHandler(async (req, res) => {
    const q = listQuery.extend({ lowStock: boolish.optional() }).parse(req.query);
    res.json(await products.listProducts({ ...q, sort: q.sort || 'newest', includeInactive: true, ...paging(req.query, 20, 100) }));
  }),
);

router.post(
  '/',
  authenticate,
  authorize(P.PRODUCT_MANAGE),
  imageUpload.single('image'),
  validate(productBody),
  asyncHandler(async (req, res) => {
    const data = { ...req.body, imageUrl: publicUploadUrl(req.file) || req.body.imageUrl || null };
    res.status(201).json(await products.createProduct(data, req.user.id));
  }),
);

router.put(
  '/:id',
  authenticate,
  authorize(P.PRODUCT_MANAGE),
  validate(idParam, 'params'),
  imageUpload.single('image'),
  validate(productBody.partial()),
  asyncHandler(async (req, res) => {
    const data = { ...req.body };
    const uploaded = publicUploadUrl(req.file);
    if (uploaded) data.imageUrl = uploaded;
    res.json(await products.updateProduct(req.params.id, data, req.user.id));
  }),
);

// Staff (inventory) may only change stock levels.
router.patch(
  '/:id/stock',
  authenticate,
  authorize(P.PRODUCT_UPDATE_STOCK),
  validate(idParam, 'params'),
  validate(z.object({ stock: z.coerce.number().int().min(0).max(100_000) })),
  asyncHandler(async (req, res) => {
    res.json(await products.updateProduct(req.params.id, { stock: req.body.stock }, req.user.id));
  }),
);

router.delete(
  '/:id',
  authenticate,
  authorize(P.PRODUCT_MANAGE),
  validate(idParam, 'params'),
  asyncHandler(async (req, res) => {
    await products.deleteProduct(req.params.id, req.user.id);
    res.status(204).end();
  }),
);

// ---------- categories ----------
const categoryBody = z.object({ name: z.string().trim().min(2).max(60), description: z.string().trim().max(300).optional() });

router.post(
  '/categories',
  authenticate,
  authorize(P.CATEGORY_MANAGE),
  validate(categoryBody),
  asyncHandler(async (req, res) => res.status(201).json(await products.createCategory(req.body))),
);
router.put(
  '/categories/:id',
  authenticate,
  authorize(P.CATEGORY_MANAGE),
  validate(idParam, 'params'),
  validate(categoryBody),
  asyncHandler(async (req, res) => res.json(await products.updateCategory(req.params.id, req.body))),
);
router.delete(
  '/categories/:id',
  authenticate,
  authorize(P.CATEGORY_MANAGE),
  validate(idParam, 'params'),
  asyncHandler(async (req, res) => {
    await products.deleteCategory(req.params.id);
    res.status(204).end();
  }),
);

export default router;
