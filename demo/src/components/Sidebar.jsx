import { useState, useEffect } from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Thermometer,
  Factory,
  TrendingUp,
  Bell,
  ClipboardList,
  Settings,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

const NAV_ITEMS = [
  { to: '/',             label: 'Dashboard',    icon: LayoutDashboard, end: true },
  { to: '/refrigeration', label: 'Refrigeration', icon: Thermometer },
  { to: '/production',   label: 'Production',   icon: Factory },
  { to: '/trending',     label: 'Trending',     icon: TrendingUp },
  { to: '/alarms',       label: 'Alarms',       icon: Bell, badge: true },
  { to: '/audit',        label: 'Audit',        icon: ClipboardList },
  { to: '/system',       label: 'System',       icon: Settings },
];

/**
 * Collapsible sidebar nav.
 *
 * @param {{ alarmCount?: number }} props
 */
export default function Sidebar({ alarmCount = 0 }) {
  const { canAdmin } = useAuth();
  // Collapse by default on narrow screens
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 1024);

  // Keep collapsed state in sync with viewport resize
  useEffect(() => {
    function onResize() {
      if (window.innerWidth < 1024) setCollapsed(true);
    }
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const width = collapsed ? 'w-16' : 'w-56';

  return (
    <aside
      className={`fixed left-0 top-14 bottom-0 ${width} bg-slate-900 flex flex-col transition-all duration-200 z-10`}
    >
      {/* Nav items */}
      <nav className="flex-1 py-3 overflow-y-auto overflow-x-hidden">
        {NAV_ITEMS.filter(({ to }) => {
          if (to === '/system' || to === '/audit') return canAdmin;
          return true;
        }).map(({ to, label, icon: Icon, end, badge }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              `flex items-center gap-3 px-4 py-2.5 text-sm font-medium transition-colors relative
               ${isActive
                 ? 'bg-blue-600 text-white'
                 : 'text-slate-400 hover:text-white hover:bg-slate-800'
               }`
            }
          >
            <span className="relative shrink-0">
              <Icon size={18} />
              {badge && alarmCount > 0 && (
                <span className="absolute -top-1.5 -right-1.5 flex items-center justify-center w-4 h-4 text-[10px] font-bold bg-red-500 text-white rounded-full leading-none">
                  {alarmCount > 99 ? '99+' : alarmCount}
                </span>
              )}
            </span>
            {!collapsed && <span className="truncate">{label}</span>}
          </NavLink>
        ))}
      </nav>

      {/* Collapse toggle */}
      <button
        onClick={() => setCollapsed((c) => !c)}
        className="flex items-center justify-center h-10 text-slate-400 hover:text-white hover:bg-slate-800 border-t border-slate-800 shrink-0 transition-colors"
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
      >
        {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
      </button>
    </aside>
  );
}
