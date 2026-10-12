import { Flame, LogOut } from 'lucide-react';
import { useAuth } from '../context/AuthContext';

const STATUS_CONFIG = {
  connected:    { color: 'bg-emerald-500', label: 'Live',         pulse: false },
  connecting:   { color: 'bg-amber-400',   label: 'Connecting',   pulse: true  },
  error:        { color: 'bg-amber-400',   label: 'Reconnecting', pulse: true  },
  disconnected: { color: 'bg-slate-500',   label: 'Idle',         pulse: false },
};

/**
 * Fixed top header bar.
 *
 * @param {{ pageTitle?: string, sseStatus?: string }} props
 */
export default function Header({ pageTitle = '', sseStatus = 'disconnected' }) {
  const cfg = STATUS_CONFIG[sseStatus] ?? STATUS_CONFIG.disconnected;
  const { user, doLogout } = useAuth();

  return (
    <header className="fixed top-0 left-0 right-0 h-14 bg-slate-800 text-white flex items-center justify-between px-4 z-20 shadow">
      {/* Branding */}
      <div className="flex items-center gap-2 font-semibold text-base">
        <Flame size={20} className="text-orange-400" />
        <span>Dairy Plant Dashboard</span>
        {pageTitle && (
          <>
            <span className="text-slate-500 mx-1">/</span>
            <span className="text-slate-300 font-normal">{pageTitle}</span>
          </>
        )}
      </div>

      {/* Right side: SSE status + user/logout */}
      <div className="flex items-center gap-4 text-sm text-slate-300">
        {/* SSE status indicator */}
        <div className="flex items-center gap-2">
          <span
            className={`w-2 h-2 rounded-full ${cfg.color} ${cfg.pulse ? 'animate-pulse' : ''}`}
          />
          <span>{cfg.label}</span>
        </div>

        {/* User info and logout */}
        {user && (
          <div className="flex items-center gap-2 border-l border-slate-600 pl-4">
            <span className="text-slate-400 text-xs">{user}</span>
            <button
              onClick={doLogout}
              title="Sign out"
              className="text-slate-400 hover:text-white transition-colors"
            >
              <LogOut size={16} />
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
