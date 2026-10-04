import axios from 'axios';

/**
 * Axios instance for the Bloom API.
 *  - The short-lived access token lives in memory only (never localStorage).
 *  - The refresh token is an httpOnly cookie; on a 401 we call /auth/refresh once
 *    (single-flight, shared by concurrent requests) and replay the request.
 */
export const api = axios.create({
  baseURL: `${import.meta.env.VITE_API_URL || ''}/api`,
  withCredentials: true,
  timeout: 20_000,
});

let accessToken = null;
let refreshing = null;
let onSessionExpired = () => {};

export const setAccessToken = (t) => {
  accessToken = t;
};
export const setSessionExpiredHandler = (fn) => {
  onSessionExpired = fn;
};

export function refreshSession() {
  if (!refreshing) {
    refreshing = axios
      .post(`${api.defaults.baseURL}/auth/refresh`, null, { withCredentials: true })
      .then((r) => {
        accessToken = r.data.accessToken;
        return r.data;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

api.interceptors.request.use((config) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config;
    const isAuthCall = original?.url?.startsWith('/auth/');
    if (error.response?.status === 401 && accessToken && !original._retry && !isAuthCall) {
      original._retry = true;
      try {
        await refreshSession();
        return api(original);
      } catch {
        accessToken = null;
        onSessionExpired();
      }
    }
    return Promise.reject(error);
  },
);

/** Human-readable message from an API error. */
export function errorMessage(err, fallback = 'Something went wrong. Please try again.') {
  if (err?.response?.data?.message) return err.response.data.message;
  if (err?.code === 'ECONNABORTED') return 'The server took too long to respond.';
  if (err?.request && !err.response) return 'Cannot reach the server. Check your connection.';
  return fallback;
}

/** Field errors from a zod validation response -> { field: message } */
export const fieldErrors = (err) =>
  Object.fromEntries((err?.response?.data?.details || []).map((d) => [d.field, d.message]));
