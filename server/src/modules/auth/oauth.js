import crypto from 'node:crypto';
import { env } from '../../config/env.js';
import { badRequest } from '../../utils/http.js';

/**
 * OAuth2 Authorization Code flow with PKCE against Google (OpenID Connect).
 *
 *  1. /oauth/google            -> redirect to Google with state + code_challenge
 *  2. Google -> /oauth/google/callback?code&state
 *  3. exchange code (+ code_verifier) for tokens at the token endpoint
 *  4. read the verified identity from the userinfo endpoint
 */
const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';

export const googleEnabled = () => Boolean(env.google.clientId && env.google.clientSecret);

const base64url = (buf) => buf.toString('base64url');

export function buildGoogleAuthRequest() {
  const state = base64url(crypto.randomBytes(24));
  const codeVerifier = base64url(crypto.randomBytes(48));
  const codeChallenge = base64url(crypto.createHash('sha256').update(codeVerifier).digest());
  const params = new URLSearchParams({
    client_id: env.google.clientId,
    redirect_uri: env.google.redirectUri,
    response_type: 'code',
    scope: 'openid email profile',
    state,
    code_challenge: codeChallenge,
    code_challenge_method: 'S256',
    prompt: 'select_account',
  });
  return { url: `${AUTH_URL}?${params}`, state, codeVerifier };
}

export async function exchangeGoogleCode(code, codeVerifier) {
  const tokenRes = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: env.google.clientId,
      client_secret: env.google.clientSecret,
      redirect_uri: env.google.redirectUri,
      grant_type: 'authorization_code',
      code_verifier: codeVerifier,
    }),
  });
  if (!tokenRes.ok) throw badRequest('Google rejected the authorization code');
  const tokens = await tokenRes.json();

  const infoRes = await fetch(USERINFO_URL, { headers: { Authorization: `Bearer ${tokens.access_token}` } });
  if (!infoRes.ok) throw badRequest('Could not read Google profile');
  const info = await infoRes.json();
  if (!info.email || info.email_verified === false) throw badRequest('Google account email is not verified');

  return {
    provider: 'google',
    subject: info.sub,
    email: info.email,
    firstName: info.given_name,
    lastName: info.family_name,
    avatarUrl: info.picture,
  };
}
