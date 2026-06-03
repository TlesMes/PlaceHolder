import { createContext, useContext, useState, useCallback, useMemo } from 'react';
import { decodeJwt, isTokenExpired } from '../lib/jwt';

const AuthContext = createContext(null);
const TOKEN_KEY = 'accessToken';

function readAuth() {
  const token = localStorage.getItem(TOKEN_KEY);
  const claims = decodeJwt(token);
  if (!token || !claims || isTokenExpired(claims)) {
    if (token) localStorage.removeItem(TOKEN_KEY);
    return { token: null, claims: null };
  }
  return { token, claims };
}

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState(readAuth);

  const login = useCallback((token) => {
    localStorage.setItem(TOKEN_KEY, token);
    setAuth(readAuth());
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setAuth({ token: null, claims: null });
  }, []);

  const value = useMemo(
    () => ({
      token: auth.token,
      claims: auth.claims,
      email: auth.claims?.email ?? null,
      role: auth.claims?.role ?? null,
      isAuthenticated: !!auth.token,
      isBooker: auth.claims?.role === 'BOOKER',
      login,
      logout,
    }),
    [auth, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
