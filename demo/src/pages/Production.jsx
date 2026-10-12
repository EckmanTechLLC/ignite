import { useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { Package, Hash, Play, Settings } from 'lucide-react';
import { useSse } from '../hooks/useSse';
import { getHistory } from '../api';
import DataTable from '../components/DataTable';
import HoaToggle from '../components/HoaToggle';
import KpiCard from '../components/KpiCard';

// ---------------------------------------------------------------------------
// Tag paths
// ---------------------------------------------------------------------------

const MOTOR_PATHS = [1, 2, 3, 4, 5, 6, 7, 8].flatMap((n) => [
  `[default]Overview/Motor ${n}/HOA`,
  `[default]Overview/Motor ${n}/Amps`,
]);

const PRODUCTION_PATHS = [
  ...MOTOR_PATHS,
  '[default]Overview/Palletizer 1/HOA',
  '[default]Overview/Palletizer 1/Pattern',
  '[default]Overview/Palletizer 1/Type',
  '[default]Overview/Palletizer 2/HOA',
  '[default]Overview/Palletizer 2/Pattern',
  '[default]Overview/Palletizer 2/Type',
  '[default]Overview/Pallet Wrapper/HOA',
  '[default]Overview/Pallet Wrapper/Pattern',
  '[default]Overview/Sensor 1/Temperature',
  '[default]Overview/Sensor 1/Humidity',
  '[default]Overview/Sensor 2/Temperature',
  '[default]Overview/Sensor 2/Humidity',
  '[default]Overview/Sensor 3/Temperature',
  '[default]Overview/Sensor 3/Humidity',
  '[default]Overview/CaseCount',
  '[default]Overview/CurrentOrder',
  '[default]Overview/CurrentRun',
  '[default]Overview/RunControl',
];

const MOTOR_ROWS = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({
  id: n,
  name: `Motor ${n}`,
  hoaPath: `[default]Overview/Motor ${n}/HOA`,
  ampPath: `[default]Overview/Motor ${n}/Amps`,
}));

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
// Motor table column definitions (need live values via closure)
// ---------------------------------------------------------------------------

function buildMotorColumns(values) {
  return [
    { key: 'name', label: 'Motor' },
    {
      key: 'status',
      label: 'Status',
      render: (_, row) => {
        const hoa = getVal(values, row.hoaPath);
        const n = hoa != null ? Number(hoa) : undefined;
        const dot =
          n === 2 ? 'bg-emerald-500' :
          n === 1 ? 'bg-amber-500' :
          'bg-slate-400';
        return <span className={`inline-block w-2.5 h-2.5 rounded-full ${dot}`} />;
      },
    },
    {
      key: 'amps',
      label: 'Amps',
      render: (_, row) => {
        const amp = getVal(values, row.ampPath);
        if (amp == null) return '—';
        return `${Number(amp).toFixed(1)} A`;
      },
    },
    {
      key: 'hoa',
      label: 'HOA',
      render: (_, row) => {
        const hoa = getVal(values, row.hoaPath);
        return <HoaToggle path={row.hoaPath} value={hoa} />;
      },
    },
  ];
}

// ---------------------------------------------------------------------------
// Case Count trend chart
// ---------------------------------------------------------------------------

const RANGE_OPTIONS = [
  { label: '1h',  hours: 1,   size: 100 },
  { label: '6h',  hours: 6,   size: 200 },
  { label: '24h', hours: 24,  size: 300 },
  { label: '7d',  hours: 168, size: 500 },
];

function CaseCountChart() {
  const canvasRef = useRef(null);
  const chartRef = useRef(null);
  const [rangeIdx, setRangeIdx] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    const range = RANGE_OPTIONS[rangeIdx];
    const end = new Date();
    const start = new Date(end.getTime() - range.hours * 3600 * 1000);

    setLoading(true);
    setError(null);
    getHistory(['[default]Overview/CaseCount'], start, end, range.size)
      .then((data) => {
        const rows = Array.isArray(data?.rows) ? data.rows : [];
        const points = rows
          .map((row) => ({ x: new Date(row[0]).getTime(), y: parseFloat(row[1]) }))
          .filter((p) => !isNaN(p.y));

        if (!canvasRef.current) return;
        if (chartRef.current) {
          chartRef.current.destroy();
          chartRef.current = null;
        }

        const ctx = canvasRef.current.getContext('2d');
        const color = '#3b82f6';
        const gradient = ctx.createLinearGradient(0, 0, 0, 200);
        gradient.addColorStop(0, color + '33');
        gradient.addColorStop(1, color + '00');

        chartRef.current = new Chart(ctx, {
          type: 'line',
          data: {
            datasets: [{
              label: 'Case Count',
              data: points,
              borderColor: color,
              backgroundColor: gradient,
              fill: true,
              tension: 0.3,
              pointRadius: 0,
              borderWidth: 2,
            }],
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            plugins: {
              legend: { display: false },
              tooltip: {
                callbacks: {
                  title: (items) => new Date(items[0].parsed.x).toLocaleString(),
                  label: (ctx) => `Cases: ${ctx.parsed.y}`,
                },
              },
            },
            scales: {
              x: {
                type: 'linear',
                ticks: {
                  maxTicksLimit: 6,
                  callback: (v) => {
                    const d = new Date(v);
                    return range.hours <= 24
                      ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                      : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
                  },
                },
                grid: { color: '#f1f5f9' },
              },
              y: {
                ticks: { maxTicksLimit: 5 },
                grid: { color: '#f1f5f9' },
              },
            },
          },
        });
      })
      .catch(() => setError('Failed to load history data.'))
      .finally(() => setLoading(false));

    return () => {
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, [rangeIdx]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">
          Case Count Trend
        </h3>
        <div className="flex gap-1">
          {RANGE_OPTIONS.map((opt, i) => (
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
      </div>
      {loading && <p className="text-xs text-slate-400 mb-1">Loading…</p>}
      {error && (
        <p className="text-xs text-red-500 mb-1">{error}</p>
      )}
      <div style={{ height: 200 }}>
        <canvas ref={canvasRef} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function Production() {
  const { setSseStatus } = useOutletContext();
  const { values, status } = useSse(PRODUCTION_PATHS);

  useEffect(() => { setSseStatus(status); }, [status, setSseStatus]);

  const g = (path) => getVal(values, path);
  const motorCols = buildMotorColumns(values);

  return (
    <div className="space-y-6">

      {/* Motor Status Table */}
      <div className="bg-white rounded-xl shadow-sm p-6">
        <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
          Motor Status
        </h2>
        <DataTable columns={motorCols} rows={MOTOR_ROWS} rowKey="id" />
      </div>

      {/* Packaging Equipment + Environmental Sensors */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* Packaging Equipment */}
        <div className="bg-white rounded-xl shadow-sm p-6">
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
            Packaging Equipment
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            {/* Palletizer 1 */}
            <div className="border border-slate-100 rounded-lg p-3">
              <p className="text-xs font-semibold text-slate-600 mb-2">Palletizer 1</p>
              <HoaToggle
                path="[default]Overview/Palletizer 1/HOA"
                value={g('[default]Overview/Palletizer 1/HOA')}
              />
              <div className="mt-2 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-slate-500">Pattern</span>
                  <span className="font-medium text-slate-700">
                    {g('[default]Overview/Palletizer 1/Pattern') ?? '—'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">Type</span>
                  <span className="font-medium text-slate-700">
                    {g('[default]Overview/Palletizer 1/Type') ?? '—'}
                  </span>
                </div>
              </div>
            </div>

            {/* Palletizer 2 */}
            <div className="border border-slate-100 rounded-lg p-3">
              <p className="text-xs font-semibold text-slate-600 mb-2">Palletizer 2</p>
              <HoaToggle
                path="[default]Overview/Palletizer 2/HOA"
                value={g('[default]Overview/Palletizer 2/HOA')}
              />
              <div className="mt-2 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-slate-500">Pattern</span>
                  <span className="font-medium text-slate-700">
                    {g('[default]Overview/Palletizer 2/Pattern') ?? '—'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">Type</span>
                  <span className="font-medium text-slate-700">
                    {g('[default]Overview/Palletizer 2/Type') ?? '—'}
                  </span>
                </div>
              </div>
            </div>

            {/* Pallet Wrapper */}
            <div className="border border-slate-100 rounded-lg p-3">
              <p className="text-xs font-semibold text-slate-600 mb-2">Pallet Wrapper</p>
              <HoaToggle
                path="[default]Overview/Pallet Wrapper/HOA"
                value={g('[default]Overview/Pallet Wrapper/HOA')}
              />
              <div className="mt-2 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-slate-500">Pattern</span>
                  <span className="font-medium text-slate-700">
                    {g('[default]Overview/Pallet Wrapper/Pattern') ?? '—'}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Environmental Sensors */}
        <div className="bg-white rounded-xl shadow-sm p-6">
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
            Environmental Sensors
          </h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200">
                <th className="text-left py-2 font-semibold text-slate-500">Zone</th>
                <th className="text-right py-2 font-semibold text-slate-500">Temperature</th>
                <th className="text-right py-2 font-semibold text-slate-500">Humidity</th>
              </tr>
            </thead>
            <tbody>
              {[1, 2, 3].map((n) => (
                <tr key={n} className="border-b border-slate-50 last:border-0">
                  <td className="py-2.5 text-slate-700">Sensor {n}</td>
                  <td className="py-2.5 text-right font-semibold text-slate-800">
                    {fmt(g(`[default]Overview/Sensor ${n}/Temperature`))}
                    <span className="text-xs font-normal text-slate-400 ml-0.5">°F</span>
                  </td>
                  <td className="py-2.5 text-right font-semibold text-slate-800">
                    {fmt(g(`[default]Overview/Sensor ${n}/Humidity`))}
                    <span className="text-xs font-normal text-slate-400 ml-0.5">%</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Production Counters + Case Count Trend */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* KPI counters */}
        <div className="lg:col-span-2 grid grid-cols-2 gap-4 content-start">
          <KpiCard
            icon={Package}
            label="Case Count"
            value={g('[default]Overview/CaseCount') ?? '—'}
            unit="cases"
            colorClass="bg-white"
            iconClass="text-violet-600"
          />
          <KpiCard
            icon={Hash}
            label="Current Order"
            value={g('[default]Overview/CurrentOrder') ?? '—'}
            colorClass="bg-white"
            iconClass="text-blue-600"
          />
          <KpiCard
            icon={Play}
            label="Current Run"
            value={g('[default]Overview/CurrentRun') ?? '—'}
            colorClass="bg-white"
            iconClass="text-emerald-600"
          />
          <KpiCard
            icon={Settings}
            label="Run Control"
            value={g('[default]Overview/RunControl') ?? '—'}
            colorClass="bg-white"
            iconClass="text-amber-600"
          />
        </div>

        {/* Case Count trend chart */}
        <div className="lg:col-span-3 bg-white rounded-xl shadow-sm p-6">
          <CaseCountChart />
        </div>
      </div>

    </div>
  );
}
