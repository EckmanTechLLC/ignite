import { createContext, useContext, useState, useEffect } from 'react';
import { getSession, login, logout } from '../api';

const AuthContext = createContext({
  user: null,
  authenticated: false,
  canWrite: false,
  canAdmin: false,
  loading: true,
  doLogin: async () => {},
  doLogout: async () => {},
});

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState({
    user: null,
    authenticated: false,
    canWrite: false,
    canAdmin: false,
    loading: true,
  });

  // Validate any stored token / existing session. Re-run on 401 (ignite:unauthorized).
  useEffect(() => {
    const loadSession = () =>
      getSession()
        .then((data) => {
          if (data.authenticated) {
            setAuth({
              user: data.user,
              authenticated: true,
              canWrite: data.permissions?.canWrite ?? false,
              canAdmin: data.permissions?.canAdmin ?? false,
              loading: false,
            });
          } else {
            // Token expired or invalid
            localStorage.removeItem('ignite_token');
            setAuth({ user: null, authenticated: false, canWrite: false, canAdmin: false, loading: false });
          }
        })
        .catch(() => {
          localStorage.removeItem('ignite_token');
          setAuth({ user: null, authenticated: false, canWrite: false, canAdmin: false, loading: false });
        });

    loadSession();
    window.addEventListener('ignite:unauthorized', loadSession);
    return () => window.removeEventListener('ignite:unauthorized', loadSession);
  }, []);

  async function doLogin(username, password) {
    const data = await login(username, password);
    localStorage.setItem('ignite_token', data.token);

    // Fetch full session to get permissions
    const session = await getSession();
    setAuth({
      user: session.authenticated ? session.user : data.user,
      authenticated: true,
      canWrite: session.permissions?.canWrite ?? false,
      canAdmin: session.permissions?.canAdmin ?? false,
      loading: false,
    });
  }

  async function doLogout() {
    try {
      await logout();
    } catch (_) {
      // Ignore server errors on logout
    }
    localStorage.removeItem('ignite_token');
    setAuth({
      user: null,
      authenticated: false,
      canWrite: false,
      canAdmin: false,
      loading: false,
    });
  }

  return (
    <AuthContext.Provider value={{ ...auth, doLogin, doLogout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
