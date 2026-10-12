import { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useSse } from '../hooks/useSse';
import StatusBadge from '../components/StatusBadge';
import EditableValue from '../components/EditableValue';
import RefrigerationPID from '../components/RefrigerationPID';

// ---------------------------------------------------------------------------
// Tag path builders
// ---------------------------------------------------------------------------

function compressorPaths(n) {
  const base = `[default]Refrigeration/Compressor${n}`;
  return [
    `${base}/hoa`,
    `${base}/motorAmps`,
    `${base}/lineVoltage`,
    `${base}/percentLoaded`,
    `${base}/pressureSuction`,
    `${base}/presSuctionSP`,
    `${base}/pressureDischarge`,
    `${base}/pressureInletOil`,
    `${base}/pressureInletOilFilter`,
    `${base}/tempSuction`,
    `${base}/tempDischarge`,
    `${base}/tempInletOil`,
    `${base}/tempOilSep`,
    `${base}/tonnageCurrent`,
    `${base}/tonnageIdeal`,
    `${base}/powerDemand`,
    `${base}/powerAvgUsage`,
  ];
}

function towerPaths(n) {
  const base = `[default]Refrigeration/Tower${n}`;
  return [
    `${base}/fanSpeed`,
    `${base}/temp`,
    `${base}/pH`,
    `${base}/makeup`,
    `${base}/mmOH`,
  ];
}

const REFRIG_PATHS = [
  ...compressorPaths(1),
  ...compressorPaths(2),
  ...towerPaths(1),
  ...towerPaths(2),
  '[default]Refrigeration/accumulatorLevel',
  '[default]Refrigeration/receiverLevel',
  '[default]Refrigeration/dischargePressure',
  '[default]Refrigeration/dischargeTemp',
  '[default]Refrigeration/valveDischarge',
  '[default]Refrigeration/valveKing',
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

// ---------------------------------------------------------------------------
// Shared UI pieces
// ---------------------------------------------------------------------------

function SectionHeader({ children }) {
  return (
    <h3 className="text-xs font-semibold text-slate-500 uppercase tracking-wider mt-5 mb-3">
      {children}
    </h3>
  );
}

function MetricRow({ label, value, unit }) {
  return (
    <div className="flex justify-between items-baseline py-1.5 border-b border-slate-50 last:border-0">
      <span className="text-sm text-slate-500">{label}</span>
      <span className="text-sm font-semibold text-slate-800">
        {value ?? '—'}
        {unit && <span className="text-xs font-normal text-slate-400 ml-1">{unit}</span>}
      </span>
    </div>
  );
}

function ProgressBar({ value, max = 100, colorClass = 'bg-blue-500' }) {
  const pct = value != null
    ? Math.min(100, Math.max(0, (Number(value) / max) * 100))
    : 0;
  return (
    <div className="h-2 bg-slate-200 rounded-full overflow-hidden">
      <div
        className={`h-full rounded-full transition-all duration-500 ${colorClass}`}
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

function LevelRow({ label, value, colorClass }) {
  return (
    <div>
      <div className="flex justify-between text-sm mb-1">
        <span className="text-slate-500">{label}</span>
        <span className="font-semibold text-slate-800">{fmt(value, 1)}%</span>
      </div>
      <ProgressBar value={value} colorClass={colorClass} />
    </div>
  );
}

function ValveRow({ label, value }) {
  return (
    <div>
      <div className="flex justify-between text-sm mb-1">
        <span className="text-slate-500">{label}</span>
        <span className="font-semibold text-slate-800">{fmt(value, 0)}%</span>
      </div>
      <ProgressBar value={value} colorClass="bg-amber-500" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Compressor Panel
// ---------------------------------------------------------------------------

function CompressorPanel({ num, values }) {
  const base = `[default]Refrigeration/Compressor${num}`;
  const g = (field) => getVal(values, `${base}/${field}`);

  const hoa        = g('hoa');
  const pctLoaded  = g('percentLoaded');

  return (
    <div className="bg-white rounded-xl shadow-sm p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-lg font-semibold text-slate-800">Compressor {num}</h2>
        <StatusBadge status={hoa != null ? Number(hoa) : undefined} />
      </div>

      {/* Status */}
      <SectionHeader>Status</SectionHeader>
      <div className="mb-3">
        <p className="text-3xl font-bold text-slate-900 leading-none">
          {fmt(g('motorAmps'), 1)}
          <span className="text-base font-normal text-slate-400 ml-1">A</span>
        </p>
        <p className="text-xs text-slate-400 mt-1">Motor Amps</p>
      </div>
      <MetricRow label="Line Voltage"  value={fmt(g('lineVoltage'), 0)} unit="V" />
      <div className="py-1.5 border-b border-slate-50">
        <div className="flex justify-between items-baseline mb-1">
          <span className="text-sm text-slate-500">% Loaded</span>
          <span className="text-sm font-semibold text-slate-800">{fmt(pctLoaded, 0)}%</span>
        </div>
        <ProgressBar value={pctLoaded} colorClass="bg-indigo-500" />
      </div>

      {/* Pressures */}
      <SectionHeader>Pressures</SectionHeader>
      <MetricRow label="Suction Pressure"    value={fmt(g('pressureSuction'), 1)}          unit="PSI" />
      <div className="flex justify-between items-baseline py-1.5 border-b border-slate-50">
        <span className="text-sm text-slate-500">Suction SP</span>
        <EditableValue
          path={`${base}/presSuctionSP`}
          value={g('presSuctionSP')}
          unit="PSI"
          dec={1}
        />
      </div>
      <MetricRow label="Discharge Pressure"  value={fmt(g('pressureDischarge'), 1)}        unit="PSI" />
      <MetricRow label="Inlet Oil Pressure"  value={fmt(g('pressureInletOil'), 1)}         unit="PSI" />
      <MetricRow label="Inlet Oil Filter"    value={fmt(g('pressureInletOilFilter'), 1)}   unit="PSI" />

      {/* Temperatures */}
      <SectionHeader>Temperatures</SectionHeader>
      <MetricRow label="Suction Temp"       value={fmt(g('tempSuction'), 1)}    unit="°F" />
      <MetricRow label="Discharge Temp"     value={fmt(g('tempDischarge'), 1)}  unit="°F" />
      <MetricRow label="Inlet Oil Temp"     value={fmt(g('tempInletOil'), 1)}   unit="°F" />
      <MetricRow label="Oil Separator Temp" value={fmt(g('tempOilSep'), 1)}     unit="°F" />

      {/* Performance */}
      <SectionHeader>Performance</SectionHeader>
      <div className="flex gap-3 mb-3">
        <div className="flex-1 bg-slate-50 rounded-lg p-3 text-center">
          <p className="text-xs text-slate-400 mb-0.5">Current Tonnage</p>
          <p className="text-xl font-bold text-slate-900">{fmt(g('tonnageCurrent'), 1)}</p>
          <p className="text-xs text-slate-400">tons</p>
        </div>
        <div className="flex-1 bg-slate-50 rounded-lg p-3 text-center">
          <p className="text-xs text-slate-400 mb-0.5">Ideal Tonnage</p>
          <p className="text-xl font-bold text-slate-900">{fmt(g('tonnageIdeal'), 1)}</p>
          <p className="text-xs text-slate-400">tons</p>
        </div>
      </div>
      <MetricRow label="Power Demand"    value={fmt(g('powerDemand'), 1)}    unit="kW" />
      <MetricRow label="Avg Power Usage" value={fmt(g('powerAvgUsage'), 1)}  unit="kW" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Cooling Tower Panel
// ---------------------------------------------------------------------------

function TowerPanel({ num, values }) {
  const base = `[default]Refrigeration/Tower${num}`;
  const g = (field) => getVal(values, `${base}/${field}`);

  return (
    <div className="bg-white rounded-xl shadow-sm p-6">
      <h2 className="text-lg font-semibold text-slate-800 mb-4">Cooling Tower {num}</h2>

      <div className="mb-4">
        <p className="text-xs text-slate-400 mb-0.5">Fan Speed</p>
        <p className="text-3xl font-bold text-slate-900 leading-none">
          {fmt(g('fanSpeed'), 0)}
          <span className="text-base font-normal text-slate-400 ml-1">Hz</span>
        </p>
      </div>

      <MetricRow label="Temperature"       value={fmt(g('temp'), 1)}    unit="°F"  />
      <MetricRow label="pH Level"          value={fmt(g('pH'), 2)}              />
      <MetricRow label="Makeup Water Flow" value={fmt(g('makeup'), 1)} unit="GPM" />
      <MetricRow label="mmOH"              value={fmt(g('mmOH'), 2)}                       />
    </div>
  );
}

// ---------------------------------------------------------------------------
// System Panel
// ---------------------------------------------------------------------------

function SystemPanel({ values }) {
  const g = (field) => getVal(values, `[default]Refrigeration/${field}`);

  return (
    <div className="bg-white rounded-xl shadow-sm p-6">
      <h2 className="text-lg font-semibold text-slate-800 mb-4">System</h2>

      <SectionHeader>Levels</SectionHeader>
      <div className="space-y-4 mb-2">
        <LevelRow label="Accumulator Level" value={g('accumulatorLevel')} colorClass="bg-blue-500"  />
        <LevelRow label="Receiver Level"    value={g('receiverLevel')}    colorClass="bg-cyan-500"  />
      </div>

      <SectionHeader>Pressures &amp; Temps</SectionHeader>
      <MetricRow label="Discharge Pressure" value={fmt(g('dischargePressure'), 1)} unit="PSI" />
      <MetricRow label="Discharge Temp"     value={fmt(g('dischargeTemp'), 1)}     unit="°F"  />

      <SectionHeader>Valve Positions</SectionHeader>
      <div className="space-y-4">
        <ValveRow label="Discharge Valve" value={g('valveDischarge')} />
        <ValveRow label="King Valve"      value={g('valveKing')}      />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Detail panel (slide-out) — delegates to existing card components
// ---------------------------------------------------------------------------

function DetailPanel({ type, num, values, onClose }) {
  return (
    <div className="bg-white rounded-xl shadow-sm overflow-hidden">
      {/* header bar */}
      <div className="flex items-center justify-between px-5 py-3 border-b border-slate-100">
        <h3 className="text-sm font-semibold text-slate-700">
          {type === 'compressor' && `Compressor ${num} — Detail`}
          {type === 'tower'      && `Tower ${num} — Detail`}
          {type === 'system'     && 'System — Detail'}
        </h3>
        <button
          onClick={onClose}
          className="text-slate-400 hover:text-slate-600 text-lg leading-none px-1"
          aria-label="Close detail panel"
        >
          ×
        </button>
      </div>

      <div className="p-4">
        {type === 'compressor' && <CompressorPanel num={num} values={values} />}
        {type === 'tower'      && <TowerPanel num={num} values={values} />}
        {type === 'system'     && <SystemPanel values={values} />}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function Refrigeration() {
  const { setSseStatus } = useOutletContext();
  const { values, status } = useSse(REFRIG_PATHS);
  const [selectedEquipment, setSelectedEquipment] = useState(null);

  useEffect(() => { setSseStatus(status); }, [status, setSseStatus]);

  // Close detail panel on Escape
  useEffect(() => {
    function onKey(e) {
      if (e.key === 'Escape') setSelectedEquipment(null);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div className="flex gap-5 items-start">
      {/* P&ID diagram */}
      <div className="flex-1 min-w-0">
        <RefrigerationPID
          values={values}
          onSelectEquipment={setSelectedEquipment}
          selectedEquipment={selectedEquipment}
        />
      </div>

      {/* Detail panel */}
      {selectedEquipment && (
        <div className="w-96 shrink-0 max-h-[calc(100vh-8rem)] overflow-y-auto">
          <DetailPanel
            type={selectedEquipment.type}
            num={selectedEquipment.num}
            values={values}
            onClose={() => setSelectedEquipment(null)}
          />
        </div>
      )}
    </div>
  );
}
