import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { env } from '../../config/env.js';
import { authenticate } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { asyncHandler, notFound } from '../../utils/http.js';
import * as common from '../../validation/common.js';
import * as auth from './auth.service.js';
import { buildGoogleAuthRequest, exchangeGoogleCode, googleEnabled } from './oauth.js';

const router = Router();

const REFRESH_COOKIE = 'bloom_rt';
const OAUTH_COOKIE = 'bloom_oauth';

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: env.isTest ? 1000 : 20,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { message: 'Too many attempts. Please wait a few minutes and try again.' },
});

function setRefreshCookie(res, refresh) {
  res.cookie(REFRESH_COOKIE, refresh.token, {
    httpOnly: true,
    secure: env.isProd,
    sameSite: 'lax',
    path: '/api/auth',
    // "remember me" -> persistent cookie; otherwise a browser-session cookie
    ...(refresh.remember ? { expires: refresh.expiresAt } : {}),
  });
}

const sendSession = (res, session, status = 200) => {
  setRefreshCookie(res, session.refresh);
  res.status(status).json({ accessToken: session.accessToken, user: session.user });
};

// ---------- schemas ----------
const registerSchema = z
  .object({
    firstName: common.name,
    lastName: common.name,
    email: common.email,
    phone: common.phone,
    password: common.password,
    confirmPassword: z.string(),
    address: common.address,
  })
  .refine((d) => d.password === d.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] });

const loginSchema = z.object({
  email: common.email,
  password: z.string().min(1, 'Password is required'),
  remember: z.boolean().optional().default(false),
});

// ---------- routes ----------
router.post(
  '/register',
  authLimiter,
  validate(registerSchema),
  asyncHandler(async (req, res) => {
    const user = await auth.register(req.body);
    sendSession(res, await auth.issueSession(user, false), 201);
  }),
);

router.post(
  '/login',
  authLimiter,
  validate(loginSchema),
  asyncHandler(async (req, res) => {
    const user = await auth.login(req.body);
    sendSession(res, await auth.issueSession(user, req.body.remember));
  }),
);

router.post(
  '/refresh',
  asyncHandler(async (req, res) => {
    try {
      sendSession(res, await auth.rotateRefreshToken(req.cookies[REFRESH_COOKIE]));
    } catch (err) {
      res.clearCookie(REFRESH_COOKIE, { path: '/api/auth' });
      throw err;
    }
  }),
);

router.post(
  '/logout',
  asyncHandler(async (req, res) => {
    await auth.revokeRefreshToken(req.cookies[REFRESH_COOKIE]);
    res.clearCookie(REFRESH_COOKIE, { path: '/api/auth' });
    res.status(204).end();
  }),
);

router.get(
  '/me',
  authenticate,
  asyncHandler(async (req, res) => {
    const user = await auth.getUserById(req.user.id);
    if (!user) throw notFound('User not found');
    res.json({ user: auth.toPublicUser(user) });
  }),
);

router.post(
  '/forgot-password',
  authLimiter,
  validate(z.object({ email: common.email })),
  asyncHandler(async (req, res) => {
    const resetUrl = await auth.requestPasswordReset(req.body.email);
    res.json({
      message: 'If an account exists for that email, a reset link has been sent.',
      // Convenience for local demos where no SMTP server is configured.
      ...(!env.isProd && !env.smtp.host && resetUrl ? { devResetUrl: resetUrl } : {}),
    });
  }),
);

router.post(
  '/reset-password',
  authLimiter,
  validate(z.object({ token: z.string().min(10), password: common.password })),
  asyncHandler(async (req, res) => {
    await auth.resetPassword(req.body.token, req.body.password);
    res.json({ message: 'Password updated. You can now sign in.' });
  }),
);

// ---------- OAuth2 ----------
router.get('/oauth/providers', (_req, res) => res.json({ google: googleEnabled() }));

router.get('/oauth/google', (req, res) => {
  if (!googleEnabled()) return res.redirect(`${env.clientUrl}/login?error=oauth_disabled`);
  const { url, state, codeVerifier } = buildGoogleAuthRequest();
  res.cookie(OAUTH_COOKIE, JSON.stringify({ state, codeVerifier, remember: req.query.remember === '1' }), {
    httpOnly: true,
    signed: true,
    secure: env.isProd,
    sameSite: 'lax',
    maxAge: 10 * 60 * 1000,
    path: '/api/auth/oauth',
  });
  return res.redirect(url);
});

router.get('/oauth/google/callback', async (req, res) => {
  const fail = (reason) => res.redirect(`${env.clientUrl}/login?error=${encodeURIComponent(reason)}`);
  try {
    const raw = req.signedCookies[OAUTH_COOKIE];
    res.clearCookie(OAUTH_COOKIE, { path: '/api/auth/oauth' });
    if (req.query.error) return fail('oauth_cancelled');
    if (!raw) return fail('oauth_state');
    const saved = JSON.parse(raw);
    if (!req.query.state || req.query.state !== saved.state) return fail('oauth_state');

    const identity = await exchangeGoogleCode(String(req.query.code), saved.codeVerifier);
    const user = await auth.upsertOAuthUser(identity);
    if (user.status !== 'active') return fail('account_disabled');
    const session = await auth.issueSession(user, saved.remember);
    setRefreshCookie(res, session.refresh);
    // the SPA calls /api/auth/refresh on this page to obtain its access token
    return res.redirect(`${env.clientUrl}/oauth/callback`);
  } catch (err) {
    console.error('OAuth callback failed:', err.message);
    return fail('oauth_failed');
  }
});

export default router;
