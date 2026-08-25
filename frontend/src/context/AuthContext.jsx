import { createContext, useContext, useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    try {
      const savedToken = localStorage.getItem('token');
      const savedUser = localStorage.getItem('user');
      if (savedToken && savedUser) {
        const parsed = JSON.parse(savedUser);
        if (parsed && parsed.role) {
          setToken(savedToken);
          setUser(parsed);
        } else {
          localStorage.removeItem('token');
          localStorage.removeItem('user');
        }
      }
    } catch {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
    } finally {
      setLoading(false);
    }
  }, []);

  const login = (authResponse) => {
    const { token: newToken, ...userData } = authResponse;
    setToken(newToken);
    setUser(userData);
    localStorage.setItem('token', newToken);
    localStorage.setItem('user', JSON.stringify(userData));
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  };

  const isAdmin = () => ['ADMIN', 'TL', 'TR'].includes(user?.role);
  const isUser = () => ['TRAINEE', 'INTERN', 'PPO', 'TL', 'TR'].includes(user?.role);
  const isAdminOrTL = () => ['ADMIN', 'TL', 'TR'].includes(user?.role);

  return (
    <AuthContext.Provider value={{ user, token, loading, login, logout, isAdmin, isUser, isAdminOrTL }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
