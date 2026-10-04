import multer from 'multer';
import { ZodError } from 'zod';
import { env } from '../config/env.js';
import { AppError } from '../utils/http.js';

export function notFoundHandler(req, res) {
  res.status(404).json({ message: `Route ${req.method} ${req.originalUrl} not found` });
}

// eslint-disable-next-line no-unused-vars
export function errorHandler(err, _req, res, _next) {
  if (err instanceof AppError) {
    return res.status(err.status).json({ message: err.message, details: err.details });
  }
  if (err instanceof ZodError) {
    const details = err.errors.map((e) => ({ field: e.path.join('.'), message: e.message }));
    return res.status(400).json({ message: details[0]?.message || 'Invalid input', details });
  }
  if (err instanceof multer.MulterError) {
    return res.status(400).json({ message: err.message });
  }
  // PostgreSQL constraint violations -> friendly 4xx
  if (err.code === '23505') return res.status(409).json({ message: 'That record already exists' });
  if (err.code === '23503') return res.status(409).json({ message: 'Record is referenced by other data' });
  if (err.code === '23514') return res.status(400).json({ message: 'A value is outside the allowed range' });
  if (err.type === 'entity.parse.failed') return res.status(400).json({ message: 'Malformed JSON body' });

  if (!env.isTest) console.error(err);
  return res.status(500).json({ message: 'Something went wrong on our side. Please try again.' });
}
