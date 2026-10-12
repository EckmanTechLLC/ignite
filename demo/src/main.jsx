import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import './index.css';
import App from './App';
import { AuthProvider } from './context/AuthContext';
import Dashboard from './pages/Dashboard';
import Refrigeration from './pages/Refrigeration';
import Production from './pages/Production';
import Trending from './pages/Trending';
import Alarms from './pages/Alarms';
import Audit from './pages/Audit';
import System from './pages/System';

// Resolve the base path for React Router.
// 1. Explicit env injection from gateway (IGNITE_BASE_PATH)
// 2. Auto-detect: if served under /data/ignite/s/<mount>/, derive it from the URL
// 3. Fallback to '/' for local dev
function resolveBasename() {
  const env = window.__IGNITE_ENV__?.IGNITE_BASE_PATH;
  if (env) return env;

  // Auto-detect from current path: /data/ignite/s/<mount>/...
  const match = window.location.pathname.match(/^(\/data\/ignite\/s\/[^/]+)\/?/);
  if (match) return match[1];

  return '/';
}

const basename = resolveBasename();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter basename={basename}>
      <AuthProvider>
      <Routes>
        <Route element={<App />}>
          <Route index element={<Dashboard />} />
          <Route path="refrigeration" element={<Refrigeration />} />
          <Route path="production" element={<Production />} />
          <Route path="trending" element={<Trending />} />
          <Route path="alarms" element={<Alarms />} />
          <Route path="audit" element={<Audit />} />
          <Route path="system" element={<System />} />
        </Route>
      </Routes>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>
);
