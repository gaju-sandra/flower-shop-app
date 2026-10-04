import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { imageUpload, publicUploadUrl } from '../../middleware/upload.js';
import { validate } from '../../middleware/validate.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler, badRequest, paging } from '../../utils/http.js';
import * as common from '../../validation/common.js';
import * as users from './user.service.js';

const STAFF_ROLES = ['order_manager', 'delivery_staff', 'customer_support', 'inventory_staff'];

const profileSchema = z.object({
  firstName: common.name.optional(),
  lastName: common.name.optional(),
  email: common.email.optional(),
  phone: common.phone.optional(),
});
const addressSchema = common.address.extend({
  label: z.string().trim().max(40).optional(),
  recipientName: z.string().trim().max(120).optional().or(z.literal('')),
  phone: common.phone.optional().or(z.literal('')),
  isDefault: z.boolean().optional(),
});
const listQuery = z.object({
  search: z.string().trim().max(60).optional(),
  status: z.enum(['active', 'disabled']).optional(),
  page: z.coerce.number().optional(),
  limit: z.coerce.number().optional(),
});

// =============== /api/profile (any signed-in user) ===============
export const profileRouter = Router();
profileRouter.use(authenticate, authorize(P.PROFILE_MANAGE));

profileRouter.put('/', validate(profileSchema), asyncHandler(async (req, res) => res.json(await users.updateProfile(req.user.id, req.body))));

profileRouter.post(
  '/avatar',
  imageUpload.single('avatar'),
  asyncHandler(async (req, res) => {
    if (!req.file) throw badRequest('Choose an image to upload');
    res.json(await users.setAvatar(req.user.id, publicUploadUrl(req.file)));
  }),
);

profileRouter.put(
  '/password',
  validate(
    z
      .object({ currentPassword: z.string().optional(), newPassword: common.password, confirmPassword: z.string() })
      .refine((d) => d.newPassword === d.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] }),
  ),
  asyncHandler(async (req, res) => {
    await users.changePassword(req.user.id, req.body);
    res.json({ message: 'Password updated' });
  }),
);

profileRouter.get(
  '/dashboard',
  authorize(P.ORDER_READ_OWN),
  asyncHandler(async (req, res) => res.json(await users.customerDashboard(req.user.id))),
);

// =============== /api/addresses (customers) ===============
export const addressRouter = Router();
addressRouter.use(authenticate, authorize(P.ADDRESS_MANAGE));
addressRouter.get('/', asyncHandler(async (req, res) => res.json(await users.listAddresses(req.user.id))));
addressRouter.post('/', validate(addressSchema), asyncHandler(async (req, res) => res.status(201).json(await users.saveAddress(req.user.id, req.body))));
addressRouter.put(
  '/:id',
  validate(common.idParam, 'params'),
  validate(addressSchema),
  asyncHandler(async (req, res) => res.json(await users.saveAddress(req.user.id, req.body, req.params.id))),
);
addressRouter.patch(
  '/:id/default',
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => res.json(await users.setDefaultAddress(req.user.id, req.params.id))),
);
addressRouter.delete(
  '/:id',
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => res.json(await users.deleteAddress(req.user.id, req.params.id))),
);

// =============== /api/customers (staff read, admin manage) ===============
export const customerRouter = Router();
customerRouter.use(authenticate);
customerRouter.get(
  '/',
  authorize(P.CUSTOMER_READ),
  validate(listQuery, 'query'),
  asyncHandler(async (req, res) => res.json(await users.listUsers({ role: 'customer', ...req.query, ...paging(req.query, 15, 100) }))),
);
customerRouter.get(
  '/:id',
  authorize(P.CUSTOMER_READ),
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => res.json(await users.customerDetail(req.params.id))),
);
customerRouter.put(
  '/:id',
  authorize(P.CUSTOMER_MANAGE),
  validate(common.idParam, 'params'),
  validate(profileSchema.extend({ status: z.enum(['active', 'disabled']).optional() })),
  asyncHandler(async (req, res) => res.json(await users.adminUpdateUser(req.params.id, req.body, req.user.id, { role: 'customer' }))),
);

// =============== /api/staff (admin only) ===============
export const staffRouter = Router();
staffRouter.use(authenticate, authorize(P.STAFF_MANAGE));
staffRouter.get(
  '/',
  validate(listQuery, 'query'),
  asyncHandler(async (req, res) => res.json(await users.listUsers({ role: 'staff', ...req.query, ...paging(req.query, 20, 100) }))),
);
staffRouter.post(
  '/',
  validate(
    z.object({
      firstName: common.name,
      lastName: common.name,
      email: common.email,
      phone: common.phone,
      password: common.password,
      staffRole: z.enum(STAFF_ROLES),
    }),
  ),
  asyncHandler(async (req, res) => res.status(201).json(await users.createStaff(req.body, req.user.id))),
);
staffRouter.put(
  '/:id',
  validate(common.idParam, 'params'),
  validate(profileSchema.extend({ staffRole: z.enum(STAFF_ROLES).optional(), status: z.enum(['active', 'disabled']).optional() })),
  asyncHandler(async (req, res) => res.json(await users.adminUpdateUser(req.params.id, req.body, req.user.id, { role: 'staff' }))),
);
staffRouter.delete(
  '/:id',
  validate(common.idParam, 'params'),
  asyncHandler(async (req, res) => {
    await users.deleteStaff(req.params.id, req.user.id);
    res.status(204).end();
  }),
);
