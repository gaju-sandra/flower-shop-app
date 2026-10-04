import jwt from 'jsonwebtoken';
import { env } from '../config/env.js';
import { hasPermission } from '../security/permissions.js';
import { forbidden, unauthorized } from '../utils/http.js';

/** Verifies the Bearer access token and attaches `req.user = { id, role, name }`. */
export function authenticate(req, _res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return next(unauthorized());
  try {
    const payload = jwt.verify(token, env.jwtAccessSecret, { issuer: 'bloom-api' });
    req.user = { id: Number(payload.sub), role: payload.role, name: payload.name };
    return next();
  } catch {
    return next(unauthorized('Session expired, please sign in again'));
  }
}

/** Like authenticate, but anonymous requests are allowed through. */
export function optionalAuth(req, res, next) {
  if (!req.headers.authorization) return next();
  return authenticate(req, res, next);
}

/**
 * RBAC guard: `authorize(PERMISSIONS.X, PERMISSIONS.Y)` requires ALL listed permissions.
 * Must run after `authenticate`.
 */
export const authorize =
  (...permissions) =>
  (req, _res, next) => {
    if (!req.user) return next(unauthorized());
    const ok = permissions.every((p) => hasPermission(req.user.role, p));
    return ok ? next() : next(forbidden());
  };
