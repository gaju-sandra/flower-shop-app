import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { env } from '../../config/env.js';
import { query, withTransaction } from '../../db/postgres.js';
import { publish } from '../../messaging/broker.js';
import { permissionsFor } from '../../security/permissions.js';
import { AppError, badRequest, conflict, unauthorized } from '../../utils/http.js';

const BCRYPT_ROUNDS = env.isTest ? 4 : 12;
const DUMMY_HASH = bcrypt.hashSync('timing-equaliser', BCRYPT_ROUNDS);
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

export const hashPassword = (pw) => bcrypt.hash(pw, BCRYPT_ROUNDS);

/** Public shape of a user (never exposes the password hash). */
export function toPublicUser(u) {
  return {
    id: u.id,
    firstName: u.first_name,
    lastName: u.last_name,
    email: u.email,
    phone: u.phone,
    role: u.role,
    staffRole: u.staff_role,
    status: u.status,
    avatarUrl: u.avatar_url,
    hasPassword: Boolean(u.password_hash),
    oauthProvider: u.oauth_provider,
    createdAt: u.created_at,
    permissions: permissionsFor(u.role),
  };
}

export function signAccessToken(user) {
  return jwt.sign(
    { role: user.role, name: `${user.first_name} ${user.last_name}` },
    env.jwtAccessSecret,
    { subject: String(user.id), expiresIn: env.accessTokenTtl, issuer: 'bloom-api' },
  );
}

async function createRefreshToken(userId, remember, db = { query }) {
  const token = crypto.randomBytes(48).toString('base64url');
  const days = remember ? env.refreshTtlDaysRemember : env.refreshTtlDays;
  const expiresAt = new Date(Date.now() + days * 86_400_000);
  await db.query(
    'INSERT INTO refresh_tokens (user_id, token_hash, remember, expires_at) VALUES ($1,$2,$3,$4)',
    [userId, sha256(token), remember, expiresAt],
  );
  return { token, expiresAt, remember };
}

export async function issueSession(user, remember = false) {
  const refresh = await createRefreshToken(user.id, remember);
  await query('UPDATE users SET last_login_at = NOW() WHERE id = $1', [user.id]);
  return { accessToken: signAccessToken(user), refresh, user: toPublicUser(user) };
}

export async function findUserByEmail(email) {
  const { rows } = await query('SELECT * FROM users WHERE LOWER(email) = LOWER($1)', [email]);
  return rows[0] || null;
}

export async function getUserById(id) {
  const { rows } = await query('SELECT * FROM users WHERE id = $1', [id]);
  return rows[0] || null;
}

export async function register(input) {
  if (await findUserByEmail(input.email)) throw conflict('An account with this email already exists');
  const passwordHash = await hashPassword(input.password);
  const user = await withTransaction(async (db) => {
    const { rows } = await db.query(
      `INSERT INTO users (first_name, last_name, email, phone, password_hash, role)
       VALUES ($1,$2,$3,$4,$5,'customer') RETURNING *`,
      [input.firstName, input.lastName, input.email.toLowerCase(), input.phone, passwordHash],
    );
    const u = rows[0];
    if (input.address) {
      const a = input.address;
      await db.query(
        `INSERT INTO addresses (user_id, label, recipient_name, phone, province, district, sector, street, is_default)
         VALUES ($1,'Home',$2,$3,$4,$5,$6,$7,TRUE)`,
        [u.id, `${u.first_name} ${u.last_name}`, u.phone, a.province, a.district, a.sector, a.street],
      );
    }
    await db.query('INSERT INTO cart (user_id) VALUES ($1)', [u.id]);
    return u;
  });
  publish('user.registered', {
    userId: user.id, actorId: user.id, entity: 'user', entityId: user.id,
    email: user.email, firstName: user.first_name,
  });
  return user;
}

export async function login({ email, password }) {
  const user = await findUserByEmail(email);
  // constant-ish time: always run bcrypt even when the user is missing
  const hash = user?.password_hash || DUMMY_HASH;
  const ok = await bcrypt.compare(password, hash);
  if (!user || !ok) {
    if (user && !user.password_hash) throw unauthorized('This account uses Google sign-in. Continue with Google.');
    throw unauthorized('Incorrect email or password');
  }
  if (user.status !== 'active') throw new AppError(403, 'This account has been disabled. Please contact support.');
  publish('auth.login', { actorId: user.id, entity: 'user', entityId: user.id });
  return user;
}

/** Rotates a refresh token. Re-use of a revoked token revokes every session of that user. */
export async function rotateRefreshToken(token) {
  if (!token) throw unauthorized('No session');
  return withTransaction(async (db) => {
    const { rows } = await db.query('SELECT * FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE', [sha256(token)]);
    const stored = rows[0];
    if (!stored) throw unauthorized('Session not found');
    if (stored.revoked_at) {
      await db.query('UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL', [stored.user_id]);
      throw unauthorized('Session was revoked');
    }
    if (new Date(stored.expires_at) < new Date()) throw unauthorized('Session expired');
    const { rows: users } = await db.query('SELECT * FROM users WHERE id = $1', [stored.user_id]);
    const user = users[0];
    if (!user || user.status !== 'active') throw unauthorized('Account unavailable');
    await db.query('UPDATE refresh_tokens SET revoked_at = NOW() WHERE id = $1', [stored.id]);
    const refresh = await createRefreshToken(user.id, stored.remember, db);
    return { accessToken: signAccessToken(user), refresh, user: toPublicUser(user) };
  });
}

export async function revokeRefreshToken(token) {
  if (!token) return;
  await query('UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = $1 AND revoked_at IS NULL', [sha256(token)]);
}

export async function requestPasswordReset(email) {
  const user = await findUserByEmail(email);
  if (!user || user.status !== 'active') return null; // don't reveal whether the email exists
  const token = crypto.randomBytes(32).toString('base64url');
  await query(
    `INSERT INTO password_resets (user_id, token_hash, expires_at) VALUES ($1,$2, NOW() + INTERVAL '30 minutes')`,
    [user.id, sha256(token)],
  );
  const resetUrl = `${env.clientUrl}/reset-password?token=${token}`;
  publish('auth.password_reset_requested', {
    userId: user.id, actorId: user.id, entity: 'user', entityId: user.id,
    email: user.email, firstName: user.first_name, resetUrl,
  });
  return resetUrl;
}

export async function resetPassword(token, newPassword) {
  const hash = await hashPassword(newPassword);
  await withTransaction(async (db) => {
    const { rows } = await db.query(
      `SELECT * FROM password_resets WHERE token_hash = $1 AND used_at IS NULL AND expires_at > NOW() FOR UPDATE`,
      [sha256(token)],
    );
    if (!rows[0]) throw badRequest('This reset link is invalid or has expired');
    await db.query('UPDATE password_resets SET used_at = NOW() WHERE id = $1', [rows[0].id]);
    await db.query('UPDATE users SET password_hash = $1, updated_at = NOW() WHERE id = $2', [hash, rows[0].user_id]);
    await db.query('UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL', [rows[0].user_id]);
  });
}

/** Finds or creates the local account for a verified OAuth2 identity. */
export async function upsertOAuthUser({ provider, subject, email, firstName, lastName, avatarUrl }) {
  const { rows } = await query('SELECT * FROM users WHERE oauth_provider = $1 AND oauth_subject = $2', [provider, subject]);
  if (rows[0]) return rows[0];
  const existing = await findUserByEmail(email);
  if (existing) {
    // link the identity to the existing account (email verified by the provider)
    const { rows: linked } = await query(
      `UPDATE users SET oauth_provider = $1, oauth_subject = $2, avatar_url = COALESCE(avatar_url, $3), updated_at = NOW()
       WHERE id = $4 RETURNING *`,
      [provider, subject, avatarUrl, existing.id],
    );
    return linked[0];
  }
  const user = await withTransaction(async (db) => {
    const { rows: created } = await db.query(
      `INSERT INTO users (first_name, last_name, email, role, oauth_provider, oauth_subject, avatar_url)
       VALUES ($1,$2,$3,'customer',$4,$5,$6) RETURNING *`,
      [firstName || 'Flower', lastName || 'Lover', email.toLowerCase(), provider, subject, avatarUrl],
    );
    await db.query('INSERT INTO cart (user_id) VALUES ($1)', [created[0].id]);
    return created[0];
  });
  publish('user.registered', {
    userId: user.id, actorId: user.id, entity: 'user', entityId: user.id,
    email: user.email, firstName: user.first_name, provider,
  });
  return user;
}
