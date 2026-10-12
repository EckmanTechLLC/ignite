import { useState, useCallback } from 'react';
import { RefreshCw, Search } from 'lucide-react';
import { getAuditLogs } from '../api';
import { useAuth } from '../context/AuthContext';
import DataTable from '../components/DataTable';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const RANGE_PRESETS = [
  { label: '1h',  ms: 60 * 60 * 1000 },
  { label: '6h',  ms: 6 * 60 * 60 * 1000 },
  { label: '24h', ms: 24 * 60 * 60 * 1000 },
  { label: '7d',  ms: 7 * 24 * 60 * 60 * 1000 },
  { label: '30d', ms: 30 * 24 * 60 * 60 * 1000 },
];

const DEFAULT_PRESET = '24h';

const COLUMNS = [
  {
    key: 'timestamp',
    label: 'Timestamp',
    render: (v) => v != null ? new Date(v).toLocaleString() : '—',
  },
  { key: 'actor',  label: 'Actor' },
  { key: 'action', label: 'Action' },
  { key: 'target', label: 'Target' },
  { key: 'value',  label: 'Value' },
  { key: 'source', label: 'Source' },
  { key: 'host',   label: 'Host' },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Convert datetime-local string to epoch millis, or null if empty. */
function localToMillis(str) {
  if (!str) return null;
  return new Date(str).getTime();
}

/** Convert epoch millis to datetime-local input value. */
function millisToLocal(ms) {
  if (ms == null) return '';
  const d = new Date(ms);
  // Format: YYYY-MM-DDTHH:mm
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Normalize a raw audit log entry to the table row shape. */
function normalizeEntry(entry) {
  return {
    timestamp: entry.timestamp ?? entry.eventTime ?? null,
    actor:     entry.actor ?? entry.actorName ?? '—',
    action:    entry.action ?? entry.eventType ?? '—',
    target:    entry.actionTarget ?? entry.target ?? entry.targetPath ?? '—',
    value:     entry.actionValue ?? entry.value ?? entry.newValue ?? '—',
    source:    entry.source ?? entry.originatingSystem ?? '—',
    host:      entry.host ?? entry.actorHost ?? '—',
  };
}

// ---------------------------------------------------------------------------
// Audit page
// ---------------------------------------------------------------------------

export default function Audit() {
  const { canAdmin } = useAuth();

  // Filters
  const [auditProfile, setAuditProfile] = useState('Ignite');
  const [activePreset, setActivePreset] = useState(DEFAULT_PRESET);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [target, setTarget] = useState('');

  // Results
  const [rows, setRows] = useState(null);   // null = not yet queried
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Build query params from current filter state
  const buildParams = useCallback(() => {
    const now = Date.now();
    let start;
    let end;

    if (activePreset) {
      const preset = RANGE_PRESETS.find((p) => p.label === activePreset);
      start = now - (preset?.ms ?? 24 * 60 * 60 * 1000);
      end   = now;
    } else {
      start = localToMillis(startDate);
      end   = localToMillis(endDate);
    }

    const params = { auditProfile };
    if (start != null) params.startDate = start;
    if (end   != null) params.endDate   = end;
    if (actor)  params.actor  = actor;
    if (action) params.action = action;
    if (target) params.target = target;
    return params;
  }, [auditProfile, activePreset, startDate, endDate, actor, action, target]);

  const runQuery = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAuditLogs(buildParams());
      // API returns { result: [...] } or a raw array
      const raw = Array.isArray(data) ? data : (data?.result ?? []);
      const normalized = raw.map(normalizeEntry);
      // Sort newest-first
      normalized.sort((a, b) => (b.timestamp ?? 0) - (a.timestamp ?? 0));
      setRows(normalized);
    } catch (err) {
      setError(err.message ?? 'Unknown error');
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [buildParams]);

  // Handle preset click — clears custom date inputs
  function handlePreset(label) {
    setActivePreset(label);
    setStartDate('');
    setEndDate('');
  }

  // Handle custom date change — clears preset
  function handleCustomDate(field, value) {
    setActivePreset(null);
    if (field === 'start') setStartDate(value);
    else setEndDate(value);
  }

  // Admin guard
  if (!canAdmin) {
    return (
      <div className="flex items-center justify-center h-64 text-slate-500">
        Admin access required.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-slate-800">Audit Log</h1>
        <button
          onClick={runQuery}
          disabled={loading}
          className="flex items-center gap-2 px-3 py-1.5 text-sm bg-slate-700 text-white rounded-lg hover:bg-slate-600 disabled:opacity-50 transition-colors"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>

      {/* Filter panel */}
      <div className="bg-white rounded-xl shadow-sm p-4 space-y-3">
        {/* Row 1: Profile + time presets + custom dates */}
        <div className="flex flex-wrap items-end gap-3">
          {/* Audit Profile */}
          <div className="flex flex-col gap-1 min-w-[140px]">
            <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              Audit Profile
            </label>
            <input
              type="text"
              value={auditProfile}
              onChange={(e) => setAuditProfile(e.target.value)}
              placeholder="e.g. Ignite"
              className="border border-slate-300 rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
            />
          </div>

          {/* Time range presets */}
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              Time Range
            </label>
            <div className="flex gap-1">
              {RANGE_PRESETS.map(({ label }) => (
                <button
                  key={label}
                  onClick={() => handlePreset(label)}
                  className={`px-2.5 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
                    activePreset === label
                      ? 'bg-blue-600 text-white border-blue-600'
                      : 'bg-white text-slate-600 border-slate-300 hover:bg-slate-50'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          {/* Custom start */}
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              Start
            </label>
            <input
              type="datetime-local"
              value={startDate}
              onChange={(e) => handleCustomDate('start', e.target.value)}
              className="border border-slate-300 rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
            />
          </div>

          {/* Custom end */}
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              End
            </label>
            <input
              type="datetime-local"
              value={endDate}
              onChange={(e) => handleCustomDate('end', e.target.value)}
              className="border border-slate-300 rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
            />
          </div>
        </div>

        {/* Row 2: Text filters + search button */}
        <div className="flex flex-wrap items-end gap-3">
          {[
            { label: 'Actor',  value: actor,  set: setActor,  placeholder: 'e.g. admin' },
            { label: 'Action', value: action, set: setAction, placeholder: 'e.g. Tag Write' },
            { label: 'Target', value: target, set: setTarget, placeholder: 'e.g. Compressor' },
          ].map(({ label, value, set, placeholder }) => (
            <div key={label} className="flex flex-col gap-1 min-w-[140px]">
              <label className="text-xs font-medium text-slate-500 uppercase tracking-wide">
                {label}
              </label>
              <input
                type="text"
                value={value}
                onChange={(e) => set(e.target.value)}
                placeholder={placeholder}
                className="border border-slate-300 rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              />
            </div>
          ))}

          <button
            onClick={runQuery}
            disabled={loading || !auditProfile}
            className="flex items-center gap-2 px-4 py-1.5 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-500 disabled:opacity-50 transition-colors self-end"
          >
            <Search size={14} />
            Search
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* Results */}
      {loading && (
        <div className="flex items-center justify-center h-32">
          <RefreshCw size={20} className="text-slate-400 animate-spin" />
        </div>
      )}

      {!loading && rows === null && (
        <div className="bg-white rounded-xl shadow-sm flex items-center justify-center h-32 text-slate-400 text-sm">
          Set filters and click Search to load audit entries.
        </div>
      )}

      {!loading && rows !== null && rows.length === 0 && !error && (
        <div className="bg-white rounded-xl shadow-sm flex items-center justify-center h-32 text-slate-400 text-sm">
          No audit entries found for the selected filters.
        </div>
      )}

      {!loading && rows !== null && rows.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm overflow-hidden">
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey="timestamp"
          />
          <div className="px-4 py-2.5 text-xs text-slate-500 border-t border-slate-100">
            Showing {rows.length} {rows.length === 1 ? 'entry' : 'entries'}
          </div>
        </div>
      )}
    </div>
  );
}
