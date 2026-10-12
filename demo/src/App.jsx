import { useState, useEffect, useRef } from 'react';
import { Outlet, useLocation, Navigate } from 'react-router-dom';
import Header from './components/Header';
import Sidebar from './components/Sidebar';
import LoginForm from './components/LoginForm';
import { getAlarmStatus } from './api';
import { useAuth } from './context/AuthContext';

// Map route paths to human-readable page titles
const PAGE_TITLES = {
  '/':              'Dashboard',
  '/refrigeration': 'Refrigeration',
  '/production':    'Production',
  '/trending':      'Trending',
  '/alarms':        'Alarms',
  '/audit':         'Audit',
  '/system':        'System',
};

/**
 * Root layout shell — fixed header + collapsible sidebar + scrollable content.
 * Pages rendered via <Outlet /> can update sseStatus via the setSseStatus prop
 * passed through Outlet context.
 */
export default function App() {
  const [sseStatus, setSseStatus] = useState('disconnected');
  const [alarmCount, setAlarmCount] = useState(0);
  const alarmTimerRef = useRef(null);
  const location = useLocation();
  const { authenticated, canAdmin, loading, doLogin } = useAuth();

  // Poll alarm count every 30 seconds
  useEffect(() => {
    function fetchCount() {
      getAlarmStatus()
        .then((data) => setAlarmCount(data?.count ?? 0))
        .catch(() => {});
    }
    fetchCount();
    alarmTimerRef.current = setInterval(fetchCount, 30_000);
    return () => clearInterval(alarmTimerRef.current);
  }, []);

  const pageTitle = PAGE_TITLES[location.pathname] ?? '';

  // Redirect non-admin users away from admin-only pages once auth has loaded
  if (!loading && !canAdmin && (location.pathname === '/system' || location.pathname === '/audit')) {
    return <Navigate to="/" replace />;
  }

  // If loaded and unauthenticated, show login form
  if (!loading && !authenticated) {
    return <LoginForm onLogin={doLogin} />;
  }

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Fixed top bar */}
      <Header pageTitle={pageTitle} sseStatus={sseStatus} />

      {/* Fixed sidebar — sits below the header */}
      <Sidebar alarmCount={alarmCount} />

      {/* Main content — offset for header and sidebar */}
      {/*
        The sidebar is w-56 expanded / w-16 collapsed.
        We use a left margin that matches the sidebar's expanded width as
        a baseline; the sidebar handles its own overlap via fixed positioning.
        On mobile the sidebar overlays the content when expanded.
      */}
      <main className="pt-14 pl-16 lg:pl-56 min-h-screen transition-all duration-200 overflow-x-hidden">
        <div className="p-6">
          {loading ? (
            <div className="flex items-center justify-center h-32">
              <span className="text-sm text-slate-400">Loading…</span>
            </div>
          ) : (
            <Outlet context={{ setSseStatus }} />
          )}
        </div>
      </main>
    </div>
  );
}
