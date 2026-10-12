import { useEffect, useRef, useState, useCallback } from 'react';
import { Bell, CheckCircle, RefreshCw, AlertTriangle } from 'lucide-react';
import { getAlarmStatus, getAlarmHistory, acknowledgeAlarm } from '../api';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PRIORITY_COLOR = {
  Critical:   'text-red-500',
  High:       'text-orange-500',
  Medium:     'text-amber-500',
  Low:        'text-blue-500',
  Diagnostic: 'text-slate-400',
};

const STATE_BADGE = {
  ActiveUnacked: 'bg-red-100 text-red-700',
  ActiveAcked:   'bg-amber-100 text-amber-700',
  ClearUnacked:  'bg-slate-100 text-slate-600',
  ClearAcked:    'bg-slate-100 text-slate-600',
};

const STATE_LABEL = {
  ActiveUnacked: 'Active',
  ActiveAcked:   'Acknowledged',
  ClearUnacked:  'Cleared',
  ClearAcked:    'Cleared/Acked',
};

const HISTORY_RANGES = [
  { label: '1h',  hours: 1 },
  { label: '6h',  hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d',  hours: 168 },
];

const PRIORITY_OPTIONS = ['All', 'Critical', 'High', 'Medium', 'Low', 'Diagnostic'];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function timeAgo(ts) {
  if (!ts) return '—';
  const diff = Date.now() - new Date(ts).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function fmtTs(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString();
}

function shortenPath(path) {
  if (!path) return '';
  return path.replace(/^\[.*?\]/, '').replace(/^\//, '');
}

function PriorityIcon({ priority }) {
  const cls = PRIORITY_COLOR[priority] ?? 'text-slate-400';
  return <AlertTriangle size={14} className={`shrink-0 ${cls}`} />;
}

// ---------------------------------------------------------------------------
// Active Alarms Tab
// ---------------------------------------------------------------------------

function ActiveAlarms() {
  const [alarms, setAlarms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [acking, setAcking] = useState(new Set());
  const timerRef = useRef(null);

  const fetchAlarms = useCallback(async () => {
    try {
      const data = await getAlarmStatus();
      setAlarms(Array.isArray(data?.alarms) ? data.alarms : []);
    } catch (_) {
      // keep existing data on error
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAlarms();
    timerRef.current = setInterval(fetchAlarms, 10_000);
    return () => clearInterval(timerRef.current);
  }, [fetchAlarms]);

  async function handleAck(id) {
    setAcking((s) => new Set([...s, id]));
    try {
      await acknowledgeAlarm(id);
      await fetchAlarms();
    } catch (_) {
      // silent — fetchAlarms will still update
    } finally {
      setAcking((s) => {
        const next = new Set(s);
        next.delete(id);
        return next;
      });
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16 text-slate-400 text-sm gap-2">
        <RefreshCw size={14} className="animate-spin" />
        Loading alarms…
      </div>
    );
  }

  if (alarms.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-20 gap-3">
        <CheckCircle size={48} className="text-emerald-400" />
        <p className="text-lg font-semibold text-slate-700">All Clear</p>
        <p className="text-sm text-slate-400">No active alarms at this time</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm text-left">
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50">
            <th className="px-4 py-2.5 font-semibold text-slate-500 w-8" title="Priority">!</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">Source</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">Name</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">State</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">Time</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500 text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {alarms.map((alarm, i) => {
            const source = alarm.displayPath ?? alarm.source ?? '';
            const name   = alarm.label ?? alarm.name ?? '';
            const ts     = alarm.activeTime ?? alarm.eventTime;
            const stateKey = alarm.state ?? '';
            const canAck   = !alarm.isAcked;

            return (
              <tr
                key={alarm.id ?? i}
                className="border-b border-slate-100 hover:bg-slate-50 transition-colors"
              >
                <td className="px-4 py-2.5">
                  <PriorityIcon priority={alarm.priority} />
                </td>
                <td className="px-4 py-2.5 max-w-xs">
                  <span
                    title={source}
                    className="truncate block font-mono text-xs text-slate-600"
                  >
                    {shortenPath(source) || source}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-slate-700">{name || '—'}</td>
                <td className="px-4 py-2.5">
                  <span
                    className={`inline-block px-2 py-0.5 rounded text-xs font-medium
                      ${STATE_BADGE[stateKey] ?? 'bg-slate-100 text-slate-600'}`}
                  >
                    {STATE_LABEL[stateKey] ?? stateKey}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-slate-500 text-xs whitespace-nowrap">
                  <span title={fmtTs(ts)}>{timeAgo(ts)}</span>
                </td>
                <td className="px-4 py-2.5 text-right">
                  {canAck && (
                    <button
                      onClick={() => handleAck(alarm.id)}
                      disabled={acking.has(alarm.id) || !alarm.id}
                      className="px-2.5 py-1 text-xs rounded font-medium bg-blue-600 text-white
                                 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed
                                 transition-colors"
                    >
                      {acking.has(alarm.id) ? 'Acking…' : 'Acknowledge'}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Alarm History Tab
// ---------------------------------------------------------------------------

function AlarmHistory() {
  const [alarms, setAlarms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [rangeIdx, setRangeIdx] = useState(2); // default 24h
  const [priorityFilter, setPriorityFilter] = useState('All');

  const fetchHistory = useCallback(async () => {
    setLoading(true);
    try {
      const hours = HISTORY_RANGES[rangeIdx].hours;
      const end   = new Date();
      const start = new Date(end.getTime() - hours * 3_600_000);
      const data  = await getAlarmHistory({
        start: start.toISOString(),
        end:   end.toISOString(),
      });
      setAlarms(Array.isArray(data?.alarms) ? data.alarms : []);
    } catch (_) {
      setAlarms([]);
    } finally {
      setLoading(false);
    }
  }, [rangeIdx]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  const filtered = priorityFilter === 'All'
    ? alarms
    : alarms.filter((a) => a.priority === priorityFilter);

  const capped = filtered.slice(0, 100);

  return (
    <div className="space-y-3">
      {/* Filter bar */}
      <div className="bg-white rounded-xl shadow-sm px-4 py-3 flex flex-wrap items-center gap-3">
        <div className="flex gap-1">
          {HISTORY_RANGES.map((opt, i) => (
            <button
              key={opt.label}
              onClick={() => setRangeIdx(i)}
              className={`px-2.5 py-1 text-xs rounded font-medium transition-colors ${
                i === rangeIdx
                  ? 'bg-blue-600 text-white'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <select
          value={priorityFilter}
          onChange={(e) => setPriorityFilter(e.target.value)}
          className="px-2.5 py-1 text-xs border border-slate-200 rounded bg-white
                     text-slate-700 focus:outline-none focus:ring-1 focus:ring-blue-400"
        >
          {PRIORITY_OPTIONS.map((p) => (
            <option key={p} value={p}>
              {p === 'All' ? 'All Priorities' : p}
            </option>
          ))}
        </select>

        {loading && <RefreshCw size={13} className="text-slate-400 animate-spin" />}

        <span className="text-xs text-slate-400 ml-auto">
          {capped.length < filtered.length
            ? `Showing latest 100 of ${filtered.length}`
            : `${filtered.length} event${filtered.length !== 1 ? 's' : ''}`}
        </span>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl shadow-sm overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-12 text-slate-400 text-sm gap-2">
            <RefreshCw size={14} className="animate-spin" />
            Loading…
          </div>
        ) : capped.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 gap-2">
            <Bell size={28} className="text-slate-300" />
            <p className="text-sm text-slate-400">No alarm events in this period</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50">
                  <th className="px-4 py-2.5 font-semibold text-slate-500 w-8" title="Priority">!</th>
                  <th className="px-4 py-2.5 font-semibold text-slate-500">Source</th>
                  <th className="px-4 py-2.5 font-semibold text-slate-500">Name</th>
                  <th className="px-4 py-2.5 font-semibold text-slate-500">State</th>
                  <th className="px-4 py-2.5 font-semibold text-slate-500">Active Time</th>
                  <th className="px-4 py-2.5 font-semibold text-slate-500">Ack Time</th>
                </tr>
              </thead>
              <tbody>
                {capped.map((alarm, i) => {
                  const source   = alarm.displayPath ?? alarm.source ?? '';
                  const name     = alarm.label ?? alarm.name ?? '';
                  const stateKey = alarm.state ?? '';

                  return (
                    <tr
                      key={alarm.id ?? i}
                      className="border-b border-slate-100 hover:bg-slate-50 transition-colors"
                    >
                      <td className="px-4 py-2.5">
                        <PriorityIcon priority={alarm.priority} />
                      </td>
                      <td className="px-4 py-2.5 max-w-xs">
                        <span
                          title={source}
                          className="truncate block font-mono text-xs text-slate-600"
                        >
                          {shortenPath(source) || source}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-slate-700">{name || '—'}</td>
                      <td className="px-4 py-2.5">
                        <span
                          className={`inline-block px-2 py-0.5 rounded text-xs font-medium
                            ${STATE_BADGE[stateKey] ?? 'bg-slate-100 text-slate-600'}`}
                        >
                          {STATE_LABEL[stateKey] ?? stateKey}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-slate-500 text-xs whitespace-nowrap">
                        {fmtTs(alarm.activeTime)}
                      </td>
                      <td className="px-4 py-2.5 text-slate-500 text-xs whitespace-nowrap">
                        {fmtTs(alarm.ackTime)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function Alarms() {
  const [tab, setTab] = useState('active');

  const TABS = [
    { key: 'active',  label: 'Active Alarms' },
    { key: 'history', label: 'Alarm History' },
  ];

  return (
    <div className="space-y-4">
      {/* Tab bar */}
      <div className="bg-white rounded-xl shadow-sm px-4 py-2 flex gap-1">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-4 py-1.5 rounded text-sm font-medium transition-colors ${
              tab === key
                ? 'bg-blue-600 text-white'
                : 'text-slate-600 hover:bg-slate-100'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      {tab === 'active' ? (
        <div className="bg-white rounded-xl shadow-sm overflow-hidden">
          <ActiveAlarms />
        </div>
      ) : (
        <AlarmHistory />
      )}
    </div>
  );
}
