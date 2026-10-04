import { z } from 'zod';

export const RW_PHONE = /^(\+?250|0)7[2389]\d{7}$/;

export const name = z.string().trim().min(2, 'Name is too short').max(60);
export const email = z.string().trim().toLowerCase().email('Enter a valid email address').max(160);
export const phone = z
  .string()
  .trim()
  .transform((s) => s.replace(/[\s-]/g, ''))
  .refine((s) => RW_PHONE.test(s), 'Enter a valid Rwandan phone number, e.g. 0788123456');
export const password = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(72)
  .regex(/[a-z]/, 'Password needs a lowercase letter')
  .regex(/[A-Z]/, 'Password needs an uppercase letter')
  .regex(/\d/, 'Password needs a number');

export const address = z.object({
  province: z.string().trim().min(2, 'Province is required').max(60),
  district: z.string().trim().min(2, 'District is required').max(60),
  sector: z.string().trim().min(2, 'Sector is required').max(60),
  street: z.string().trim().min(3, 'Street / house address is required').max(200),
  locationDescription: z.string().trim().max(300).optional().or(z.literal('')),
});

/** Query-string / form boolean: only "true", "1" or true are truthy. */
export const boolish = z.preprocess((v) => v === true || v === 'true' || v === '1', z.boolean());

/** Optional foreign key from a form ("" / "null" -> null). */
export const optionalFk = z
  .preprocess((v) => (v === '' || v === 'null' || v === undefined ? null : v), z.coerce.number().int().positive().nullable())
  .optional();

export const id = z.coerce.number().int().positive();
export const idParam = z.object({ id });
