import { useEffect, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { Thermometer, Droplets, Package, Gauge, CheckCircle, AlertTriangle, Play, Pause, RotateCcw, Repeat, Hash } from 'lucide-react';
import { useSse } from '../hooks/useSse';
import { getAlarmStatus, writeTags } from '../api';
import KpiCard from '../components/KpiCard';
import StatusBadge from '../components/StatusBadge';
import HoaToggle from '../components/HoaToggle';
import { useAuth } from '../context/AuthContext';

// ---------------------------------------------------------------------------
// Tag path lists — all subscribed in a single SSE connection
// ---------------------------------------------------------------------------

const MOTOR_PATHS = [1, 2, 3, 4, 5, 6, 7, 8].flatMap((n) => [
  `[default]Overview/Motor ${n}/HOA`,
  `[default]Overview/Motor ${n}/Amps`,
]);

const CONTROL_PATHS = [
  '[default]_Controls_/Pause',
  '[default]_Controls_/Reset',
  '[default]_Controls_/Repeat',
  '[default]_Controls_/Base Rate',
  '[default]_Controls_/Program Counter',
];

const DASH_PATHS = [
  '[default]Refrigeration/ambientTemp',
  '[default]Refrigeration/ambientHum',
  '[default]Refrigeration/dischargePressure',
  '[default]Overview/CaseCount',
  ...MOTOR_PATHS,
  '[default]Overview/Palletizer 1/HOA',
  '[default]Overview/Palletizer 2/HOA',
  '[default]Overview/Pallet Wrapper/HOA',
  '[default]Overview/AU 1/Fan 1 HOA',
  '[default]Overview/AU 2/Fan 1 HOA',
  '[default]Overview/AU 3/Fan 1 HOA',
  '[default]Refrigeration/Compressor1/hoa',
  '[default]Refrigeration/Compressor1/motorAmps',
  '[default]Refrigeration/Compressor2/hoa',
  '[default]Refrigeration/Compressor2/motorAmps',
  '[default]Refrigeration/Tower1/fanSpeed',
  '[default]Refrigeration/Tower2/fanSpeed',
  ...CONTROL_PATHS,
];

// ---------------------------------------------------------------------------
// Equipment items for the status grid
// ---------------------------------------------------------------------------

const EQUIPMENT = [
  ...[1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({
    name: `Motor ${n}`,
    hoaPath: `[default]Overview/Motor ${n}/HOA`,
    ampPath: `[default]Overview/Motor ${n}/Amps`,
    hoaWritable: true,
  })),
  { name: 'Palletizer 1',  hoaPath: '[default]Overview/Palletizer 1/HOA',    hoaWritable: true },
  { name: 'Palletizer 2',  hoaPath: '[default]Overview/Palletizer 2/HOA',    hoaWritable: true },
  { name: 'Pallet Wrapper',hoaPath: '[default]Overview/Pallet Wrapper/HOA',  hoaWritable: true },
  { name: 'Air Unit 1',    hoaPath: '[default]Overview/AU 1/Fan 1 HOA',      hoaWritable: true },
  { name: 'Air Unit 2',    hoaPath: '[default]Overview/AU 2/Fan 1 HOA',      hoaWritable: true },
  { name: 'Air Unit 3',    hoaPath: '[default]Overview/AU 3/Fan 1 HOA',      hoaWritable: true },
  {
    name: 'Compressor 1',
    hoaPath: '[default]Refrigeration/Compressor1/hoa',
    ampPath: '[default]Refrigeration/Compressor1/motorAmps',
    hoaWritable: false,   // Float8, not a 0/1/2 mode selector
  },
  {
    name: 'Compressor 2',
    hoaPath: '[default]Refrigeration/Compressor2/hoa',
    ampPath: '[default]Refrigeration/Compressor2/motorAmps',
    hoaWritable: false,
  },
  { name: 'Tower 1', speedPath: '[default]Refrigeration/Tower1/fanSpeed' },
  { name: 'Tower 2', speedPath: '[default]Refrigeration/Tower2/fanSpeed' },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getVal(values, path) {
  return values[path]?.value;
}

function fmt(v, dec = 1) {
  if (v == null) return '—';
  return Number(v).toFixed(dec);
}

function ambientTempStyle(v) {
  if (v == null) return { colorClass: 'bg-white', iconClass: 'text-slate-400' };
  const n = Number(v);
  if (n < 85) return { colorClass: 'bg-emerald-50', iconClass: 'text-emerald-600' };
  if (n < 95) return { colorClass: 'bg-amber-50',   iconClass: 'text-amber-600' };
  return       { colorClass: 'bg-red-50',     iconClass: 'text-red-600' };
}

function timeAgo(ts) {
  if (!ts) return '';
  const diff = Date.now() - new Date(ts).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60)  return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60)  return `${m}m ago`;
  return `${Math.floor(m / 60)}h ago`;
}

function shortenPath(path) {
  if (!path) return '';
  return path.replace(/^\[.*?\]/, '').replace(/^\//, '');
}

const PRIORITY_COLOR = {
  1: 'text-red-500',
  2: 'text-orange-500',
  3: 'text-amber-500',
};

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function EquipmentCard({ item, values }) {
  const hoaVal   = item.hoaPath   ? getVal(values, item.hoaPath)   : null;
  const ampVal   = item.ampPath   ? getVal(values, item.ampPath)   : null;
  const speedVal = item.speedPath ? getVal(values, item.speedPath) : null;

  return (
    <div className="border border-slate-100 rounded-lg p-3">
      <p className="text-xs font-medium text-slate-600 truncate mb-1">{item.name}</p>
      {item.hoaPath ? (
        item.hoaWritable ? (
          <HoaToggle path={item.hoaPath} value={hoaVal} />
        ) : (
          <StatusBadge status={hoaVal != null ? Number(hoaVal) : undefined} />
        )
      ) : (
        <span className="text-xs text-slate-400">—</span>
      )}
      {ampVal != null && (
        <p className="text-xs text-slate-400 mt-1">{Number(ampVal).toFixed(1)} A</p>
      )}
      {speedVal != null && (
        <p className="text-xs text-slate-400 mt-1">{Number(speedVal).toFixed(0)} Hz</p>
      )}
    </div>
  );
}

function AlarmItem({ alarm, idx }) {
  const source = alarm.source ?? alarm.sourcePath ?? alarm.path ?? '';
  const val    = alarm.displayValue ?? alarm.value ?? '';
  const ts     = alarm.eventTime ?? alarm.activeTime ?? alarm.timestamp;
  const pri    = alarm.priority;

  return (
    <div className="flex items-start gap-2 p-2 rounded-lg bg-slate-50">
      <AlertTriangle
        size={14}
        className={`shrink-0 mt-0.5 ${PRIORITY_COLOR[pri] ?? 'text-slate-400'}`}
      />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium text-slate-700 truncate">
          {shortenPath(source) || `Alarm ${idx + 1}`}
        </p>
        <p className="text-xs text-slate-500 truncate">
          {val && <span>{val} · </span>}
          <span className="text-slate-400">{timeAgo(ts)}</span>
        </p>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Simulator Controls panel
// ---------------------------------------------------------------------------

function SimulatorControls({ values }) {
  const { canWrite } = useAuth();
  const [baseRateDraft, setBaseRateDraft] = useState(null);
  const [writing, setWriting] = useState({});
  const [flash, setFlash] = useState({});

  const paused      = getVal(values, '[default]_Controls_/Pause');
  const repeat      = getVal(values, '[default]_Controls_/Repeat');
  const baseRate    = getVal(values, '[default]_Controls_/Base Rate');
  const progCounter = getVal(values, '[default]_Controls_/Program Counter');

  async function doWrite(path, value, key) {
    setWriting((w) => ({ ...w, [key]: true }));
    setFlash((f) => ({ ...f, [key]: null }));
    try {
      await writeTags([{ path, value }]);
      setFlash((f) => ({ ...f, [key]: 'ok' }));
    } catch (_) {
      setFlash((f) => ({ ...f, [key]: 'err' }));
    } finally {
      setWriting((w) => ({ ...w, [key]: false }));
      setTimeout(() => setFlash((f) => ({ ...f, [key]: null })), 1200);
    }
  }

  function flashClass(key) {
    if (flash[key] === 'ok')  return 'ring-2 ring-emerald-400';
    if (flash[key] === 'err') return 'ring-2 ring-red-400';
    return '';
  }

  function commitBaseRate() {
    if (baseRateDraft === null) return;
    const n = parseInt(baseRateDraft, 10);
    if (!isNaN(n)) doWrite('[default]_Controls_/Base Rate', n, 'baseRate');
    setBaseRateDraft(null);
  }

  return (
    <div className="bg-white rounded-xl shadow-sm p-6">
      <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
        Simulator Controls
      </h2>

      <div className="space-y-3">

        {/* Pause */}
        <div className="flex items-center justify-between">
          <span className="text-sm text-slate-600 flex items-center gap-1.5">
            <Pause size={14} className="text-slate-400" />
            Simulation
          </span>
          <button
            disabled={!canWrite || writing.pause}
            onClick={() => doWrite('[default]_Controls_/Pause', !paused, 'pause')}
            className={`px-3 py-1 text-xs font-semibold rounded-full transition-all ${flashClass('pause')} ${
              paused
                ? 'bg-amber-100 text-amber-700 hover:bg-amber-200'
                : 'bg-emerald-100 text-emerald-700 hover:bg-emerald-200'
            } disabled:opacity-60 disabled:cursor-default`}
          >
            {paused ? 'Paused' : 'Running'}
          </button>
        </div>

        {/* Repeat */}
        <div className="flex items-center justify-between">
          <span className="text-sm text-slate-600 flex items-center gap-1.5">
            <Repeat size={14} className="text-slate-400" />
            Repeat
          </span>
          <button
            disabled={!canWrite || writing.repeat}
            onClick={() => doWrite('[default]_Controls_/Repeat', !repeat, 'repeat')}
            className={`px-3 py-1 text-xs font-semibold rounded-full transition-all ${flashClass('repeat')} ${
              repeat
                ? 'bg-blue-100 text-blue-700 hover:bg-blue-200'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
            } disabled:opacity-60 disabled:cursor-default`}
          >
            {repeat ? 'On' : 'Off'}
          </button>
        </div>

        {/* Reset */}
        <div className="flex items-center justify-between">
          <span className="text-sm text-slate-600 flex items-center gap-1.5">
            <RotateCcw size={14} className="text-slate-400" />
            Reset
          </span>
          <button
            disabled={!canWrite || writing.reset}
            onClick={() => doWrite('[default]_Controls_/Reset', true, 'reset')}
            className={`px-3 py-1 text-xs font-semibold rounded bg-slate-100 text-slate-600 hover:bg-red-100 hover:text-red-700 transition-colors ${flashClass('reset')} disabled:opacity-60 disabled:cursor-default`}
          >
            Reset Simulation
          </button>
        </div>

        {/* Base Rate */}
        <div className="flex items-center justify-between">
          <span className="text-sm text-slate-600 flex items-center gap-1.5">
            <Play size={14} className="text-slate-400" />
            Base Rate
          </span>
          <div className={`flex items-center gap-1.5 rounded ${flashClass('baseRate')}`}>
            <input
              type="number"
              disabled={!canWrite}
              value={baseRateDraft ?? (baseRate != null ? String(Math.round(Number(baseRate))) : '')}
              onChange={(e) => setBaseRateDraft(e.target.value)}
              onBlur={commitBaseRate}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); commitBaseRate(); }
                if (e.key === 'Escape') setBaseRateDraft(null);
              }}
              className="w-20 text-sm font-semibold text-right border border-slate-200 rounded px-1.5 py-0.5 focus:outline-none focus:border-blue-400 disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-default"
            />
            <span className="text-xs text-slate-400">ms</span>
          </div>
        </div>

        {/* Program Counter — read-only */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-100">
          <span className="text-sm text-slate-600 flex items-center gap-1.5">
            <Hash size={14} className="text-slate-400" />
            Program Counter
          </span>
          <span className="text-sm font-semibold text-slate-800 tabular-nums">
            {progCounter != null ? Math.round(Number(progCounter)) : '—'}
          </span>
        </div>

      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function Dashboard() {
  const { setSseStatus } = useOutletContext();
  const { values, status } = useSse(DASH_PATHS);
  const [alarms, setAlarms] = useState([]);
  const pollRef = useRef(null);

  // Propagate SSE status to header
  useEffect(() => { setSseStatus(status); }, [status, setSseStatus]);

  // Fetch alarms on mount, then every 30 s
  useEffect(() => {
    async function fetchAlarms() {
      try {
        const data = await getAlarmStatus();
        const list = Array.isArray(data)
          ? data
          : (Array.isArray(data?.alarms) ? data.alarms : []);
        setAlarms(list);
      } catch (_) {
        // retain current value on error
      }
    }
    fetchAlarms();
    pollRef.current = setInterval(fetchAlarms, 30_000);
    return () => clearInterval(pollRef.current);
  }, []);

  const ambientTemp = getVal(values, '[default]Refrigeration/ambientTemp');
  const { colorClass: tempBg, iconClass: tempIcon } = ambientTempStyle(ambientTemp);

  return (
    <div className="space-y-6">
      {/* KPI Row */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-6">
        <KpiCard
          icon={Thermometer}
          label="Ambient Temperature"
          value={fmt(ambientTemp)}
          unit="°F"
          colorClass={tempBg}
          iconClass={tempIcon}
        />
        <KpiCard
          icon={Droplets}
          label="Ambient Humidity"
          value={fmt(getVal(values, '[default]Refrigeration/ambientHum'))}
          unit="%"
          colorClass="bg-white"
          iconClass="text-blue-500"
        />
        <KpiCard
          icon={Package}
          label="Case Count"
          value={getVal(values, '[default]Overview/CaseCount') ?? '—'}
          unit="cases"
          colorClass="bg-white"
          iconClass="text-violet-600"
        />
        <KpiCard
          icon={Gauge}
          label="Discharge Pressure"
          value={fmt(getVal(values, '[default]Refrigeration/dischargePressure'))}
          unit="PSI"
          colorClass="bg-white"
          iconClass="text-indigo-600"
        />
      </div>

      {/* Equipment grid + Alarms */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        {/* Equipment Status Grid — 60% */}
        <div className="lg:col-span-3 bg-white rounded-xl shadow-sm p-6">
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
            Equipment Status
          </h2>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            {EQUIPMENT.map((item) => (
              <EquipmentCard key={item.name} item={item} values={values} />
            ))}
          </div>
        </div>

        {/* Active Alarms Panel — 40% */}
        <div className="lg:col-span-2 bg-white rounded-xl shadow-sm p-6 flex flex-col min-h-[320px]">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">
              Active Alarms
            </h2>
            <Link to="/alarms" className="text-xs text-blue-600 hover:underline">
              View All
            </Link>
          </div>

          {alarms.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-2 text-emerald-600">
              <CheckCircle size={32} />
              <p className="text-sm font-medium text-slate-600">No active alarms</p>
            </div>
          ) : (
            <div className="flex-1 overflow-auto space-y-2">
              {alarms.slice(0, 12).map((a, i) => (
                <AlarmItem key={a.id ?? i} alarm={a} idx={i} />
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Simulator Controls */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <SimulatorControls values={values} />
      </div>
    </div>
  );
}
