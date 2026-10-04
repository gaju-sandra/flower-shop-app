import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { api, refreshSession, setAccessToken, setSessionExpiredHandler } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  const startSession = useCallback((data) => {
    setAccessToken(data.accessToken);
    setUser(data.user);
    return data.user;
  }, []);

  // Restore the session from the httpOnly refresh cookie on page load.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      toast.error('Your session has expired. Please sign in again.');
    });
    refreshSession()
      .then((data) => setUser(data.user))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const login = useCallback(
    async ({ email, password, remember }) => startSession((await api.post('/auth/login', { email, password, remember })).data),
    [startSession],
  );

  const register = useCallback(async (form) => startSession((await api.post('/auth/register', form)).data), [startSession]);

  /** Used after the Google OAuth2 redirect: the server already set the refresh cookie. */
  const completeOAuth = useCallback(async () => startSession(await refreshSession()), [startSession]);

  const logout = useCallback(async () => {
    try {
      await api.post('/auth/logout');
    } finally {
      setAccessToken(null);
      setUser(null);
    }
  }, []);

  const can = useCallback((permission) => Boolean(user?.permissions?.includes(permission)), [user]);

  const value = useMemo(
    () => ({ user, setUser, loading, login, register, logout, completeOAuth, can }),
    [user, loading, login, register, logout, completeOAuth, can],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
