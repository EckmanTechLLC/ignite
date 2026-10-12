import { useEffect, useRef, useState, useCallback } from 'react';
import Chart from 'chart.js/auto';
import { ChevronRight, ChevronDown, X, RefreshCw } from 'lucide-react';
import { browseTags, getHistory } from '../api';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const CHART_COLORS = [
  '#3b82f6', // blue
  '#ef4444', // red
  '#10b981', // emerald
  '#f59e0b', // amber
  '#8b5cf6', // violet
  '#ec4899', // pink
  '#06b6d4', // cyan
  '#84cc16', // lime
  '#f97316', // orange
  '#6366f1', // indigo
];

const RANGE_OPTIONS = [
  { label: '1h',  hours: 1,   size: 100 },
  { label: '6h',  hours: 6,   size: 200 },
  { label: '24h', hours: 24,  size: 300 },
  { label: '7d',  hours: 168, size: 500 },
];

// ---------------------------------------------------------------------------
// Tag Tree Node
// ---------------------------------------------------------------------------

function TagNode({ node, selectedPaths, onToggleSelect, depth = 0 }) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState(null);
  const [loading, setLoading] = useState(false);

  const isLeaf = !node.hasChildren;
  const isSelected = selectedPaths.has(node.path);

  async function handleExpand() {
    if (isLeaf) {
      onToggleSelect(node);
      return;
    }
    if (!expanded && children === null) {
      setLoading(true);
      try {
        const data = await browseTags(node.path);
        const list = Array.isArray(data)
          ? data
          : Array.isArray(data?.tags)
          ? data.tags
          : Array.isArray(data?.children)
          ? data.children
          : [];
        setChildren(list);
      } catch (_) {
        setChildren([]);
      } finally {
        setLoading(false);
      }
    }
    setExpanded((e) => !e);
  }

  return (
    <div>
      <button
        onClick={handleExpand}
        className={`w-full flex items-center gap-1 px-2 py-1 rounded text-sm text-left transition-colors hover:bg-slate-100 ${
          isSelected ? 'text-blue-600 font-medium' : 'text-slate-700'
        }`}
        style={{ paddingLeft: `${0.5 + depth * 1}rem` }}
      >
        {!isLeaf ? (
          loading ? (
            <RefreshCw size={12} className="shrink-0 text-slate-400 animate-spin" />
          ) : expanded ? (
            <ChevronDown size={12} className="shrink-0 text-slate-400" />
          ) : (
            <ChevronRight size={12} className="shrink-0 text-slate-400" />
          )
        ) : (
          <span
            className={`shrink-0 w-2 h-2 rounded-full border ${
              isSelected ? 'bg-blue-500 border-blue-500' : 'border-slate-300'
            }`}
          />
        )}
        <span className="truncate">{node.name}</span>
      </button>

      {expanded && children && (
        <div>
          {children.map((child, i) => (
            <TagNode
              key={child.path ?? child.name ?? i}
              node={child}
              selectedPaths={selectedPaths}
              onToggleSelect={onToggleSelect}
              depth={depth + 1}
            />
          ))}
          {children.length === 0 && (
            <p className="text-xs text-slate-400 py-1" style={{ paddingLeft: `${0.5 + (depth + 1) * 1}rem` }}>
              No children
            </p>
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tag Picker Sidebar
// ---------------------------------------------------------------------------

function TagPicker({ selectedPaths, onToggleSelect }) {
  const [roots, setRoots] = useState(null);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    browseTags('')
      .then((data) => {
        const list = Array.isArray(data)
          ? data
          : Array.isArray(data?.tags)
          ? data.tags
          : Array.isArray(data?.children)
          ? data.children
          : [];
        setRoots(list);
      })
      .catch(() => setRoots([]));
  }, []);

  const visible = roots
    ? filter
      ? roots.filter((n) => n.name?.toLowerCase().includes(filter.toLowerCase()))
      : roots
    : null;

  return (
    <div className="flex flex-col h-full">
      <div className="p-2 border-b border-slate-200">
        <input
          type="text"
          placeholder="Filter tags…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="w-full px-2 py-1.5 text-xs border border-slate-200 rounded focus:outline-none focus:ring-1 focus:ring-blue-400"
        />
      </div>
      <div className="flex-1 overflow-y-auto p-1">
        {visible === null ? (
          <p className="text-xs text-slate-400 p-2">Loading…</p>
        ) : visible.length === 0 ? (
          <p className="text-xs text-slate-400 p-2">No tags found</p>
        ) : (
          visible.map((node, i) => (
            <TagNode
              key={node.path ?? node.name ?? i}
              node={node}
              selectedPaths={selectedPaths}
              onToggleSelect={onToggleSelect}
            />
          ))
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Multi-tag history chart
// ---------------------------------------------------------------------------

function TrendChart({ selectedTags, rangeIdx }) {
  const canvasRef = useRef(null);
  const chartRef = useRef(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [lastValues, setLastValues] = useState({});

  const fetchAndRender = useCallback(async () => {
    if (selectedTags.length === 0) {
      if (chartRef.current) {
        chartRef.current.destroy();
        chartRef.current = null;
      }
      setLastValues({});
      return;
    }

    const range = RANGE_OPTIONS[rangeIdx];
    const end = new Date();
    const start = new Date(end.getTime() - range.hours * 3600 * 1000);

    setLoading(true);
    setError(null);
    try {
      const data = await getHistory(
        selectedTags.map((t) => t.path),
        start,
        end,
        range.size
      );

      const columns = Array.isArray(data?.columns) ? data.columns : [];
      const rows = Array.isArray(data?.rows) ? data.rows : [];

      // Build dataset per tag (columns[0]=t_stamp, columns[1..N]=values)
      const newLastValues = {};
      const datasets = selectedTags.map((tag, idx) => {
        const colIdx = columns.indexOf(tag.path) !== -1
          ? columns.indexOf(tag.path)
          : idx + 1; // fallback: positional

        const points = rows
          .map((row) => ({
            x: new Date(row[0]).getTime(),
            y: parseFloat(row[colIdx]),
          }))
          .filter((p) => !isNaN(p.y));

        if (points.length > 0) {
          newLastValues[tag.path] = points[points.length - 1].y;
        }

        const color = CHART_COLORS[idx % CHART_COLORS.length];
        return {
          label: tag.name,
          data: points,
          borderColor: color,
          backgroundColor: color + '22',
          fill: false,
          tension: 0.3,
          pointRadius: 0,
          borderWidth: 2,
        };
      });

      setLastValues(newLastValues);

      if (!canvasRef.current) return;

      if (chartRef.current) {
        chartRef.current.destroy();
        chartRef.current = null;
      }

      const ctx = canvasRef.current.getContext('2d');
      chartRef.current = new Chart(ctx, {
        type: 'line',
        data: { datasets },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: false,
          interaction: {
            mode: 'index',
            intersect: false,
          },
          plugins: {
            legend: {
              display: true,
              position: 'top',
              labels: { boxWidth: 12, font: { size: 11 } },
            },
            tooltip: {
              callbacks: {
                title: (items) => new Date(items[0].parsed.x).toLocaleString(),
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
              ticks: { maxTicksLimit: 6 },
              grid: { color: '#f1f5f9' },
            },
          },
        },
      });
    } catch (_) {
      setError('Failed to load history data. Check gateway connection.');
    } finally {
      setLoading(false);
    }
  }, [selectedTags, rangeIdx]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    fetchAndRender();
    return () => {
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, [fetchAndRender]);

  return { loading, error, lastValues, refetch: fetchAndRender, canvasRef };
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function Trending() {
  const [selectedTags, setSelectedTags] = useState([]);
  const [rangeIdx, setRangeIdx] = useState(0);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const refreshTimerRef = useRef(null);

  // Stable set of selected paths for quick lookup
  const selectedPaths = new Set(selectedTags.map((t) => t.path));

  function toggleTag(node) {
    setSelectedTags((prev) => {
      if (prev.some((t) => t.path === node.path)) {
        return prev.filter((t) => t.path !== node.path);
      }
      if (prev.length >= CHART_COLORS.length) return prev; // max 10
      return [...prev, { name: node.name, path: node.path }];
    });
  }

  function removeTag(path) {
    setSelectedTags((prev) => prev.filter((t) => t.path !== path));
  }

  function clearAll() {
    setSelectedTags([]);
  }

  const { loading, error, lastValues, refetch, canvasRef } = TrendChart({ selectedTags, rangeIdx });

  // Auto-refresh interval
  useEffect(() => {
    if (refreshTimerRef.current) {
      clearInterval(refreshTimerRef.current);
      refreshTimerRef.current = null;
    }
    if (autoRefresh) {
      refreshTimerRef.current = setInterval(async () => {
        setRefreshing(true);
        await refetch();
        setRefreshing(false);
      }, 30_000);
    }
    return () => {
      if (refreshTimerRef.current) clearInterval(refreshTimerRef.current);
    };
  }, [autoRefresh, refetch]);

  return (
    <div className="flex flex-col gap-4 h-full">

      {/* Toolbar */}
      <div className="bg-white rounded-xl shadow-sm px-4 py-3 flex flex-wrap items-center gap-3">
        {/* Time range */}
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

        <span className="text-xs text-slate-500">
          {selectedTags.length} tag{selectedTags.length !== 1 ? 's' : ''} selected
        </span>

        <label className="flex items-center gap-1.5 text-xs text-slate-600 cursor-pointer select-none">
          <input
            type="checkbox"
            checked={autoRefresh}
            onChange={(e) => setAutoRefresh(e.target.checked)}
            className="accent-blue-600"
          />
          Auto-refresh (30s)
          {refreshing && (
            <RefreshCw size={11} className="text-blue-500 animate-spin" />
          )}
        </label>

        {selectedTags.length > 0 && (
          <button
            onClick={clearAll}
            className="ml-auto text-xs text-slate-500 hover:text-red-500 transition-colors"
          >
            Clear all
          </button>
        )}
      </div>

      {/* Main area: Tag Picker + Chart (side-by-side on lg+, stacked below) */}
      <div className="flex flex-col lg:flex-row gap-4 min-h-0 flex-1">

        {/* Tag Picker */}
        <div className="w-full lg:w-56 shrink-0 bg-white rounded-xl shadow-sm overflow-hidden flex flex-col" style={{ maxHeight: 400 }}>
          <div className="px-3 py-2 border-b border-slate-100">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Tags</p>
          </div>
          <TagPicker selectedPaths={selectedPaths} onToggleSelect={toggleTag} />
        </div>

        {/* Chart */}
        <div className="flex-1 bg-white rounded-xl shadow-sm p-4 flex flex-col min-w-0">
          {loading && (
            <p className="text-xs text-slate-400 mb-2 flex items-center gap-1">
              <RefreshCw size={11} className="animate-spin" /> Loading…
            </p>
          )}
          {error && (
            <div className="mb-2 px-3 py-2 bg-red-50 border border-red-200 rounded text-xs text-red-600">
              {error}
            </div>
          )}
          {selectedTags.length === 0 ? (
            <div className="flex-1 flex items-center justify-center text-slate-400 text-sm">
              Select tags from the panel to start trending
            </div>
          ) : (
            <div style={{ height: 320 }}>
              <canvas ref={canvasRef} />
            </div>
          )}
        </div>
      </div>

      {/* Selected Tags Table */}
      {selectedTags.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50">
                <th className="px-4 py-2.5 text-left font-semibold text-slate-500 w-8">Color</th>
                <th className="px-4 py-2.5 text-left font-semibold text-slate-500">Tag Path</th>
                <th className="px-4 py-2.5 text-right font-semibold text-slate-500">Last Value</th>
                <th className="px-4 py-2.5 w-10" />
              </tr>
            </thead>
            <tbody>
              {selectedTags.map((tag, idx) => {
                const color = CHART_COLORS[idx % CHART_COLORS.length];
                const lastVal = lastValues[tag.path];
                return (
                  <tr key={tag.path} className="border-b border-slate-100 hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-2.5">
                      <span
                        className="inline-block w-3 h-3 rounded-full"
                        style={{ backgroundColor: color }}
                      />
                    </td>
                    <td className="px-4 py-2.5 text-slate-700 font-mono text-xs">{tag.path}</td>
                    <td className="px-4 py-2.5 text-right text-slate-800 font-semibold">
                      {lastVal != null ? Number(lastVal).toLocaleString(undefined, { maximumFractionDigits: 2 }) : '—'}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <button
                        onClick={() => removeTag(tag.path)}
                        className="text-slate-400 hover:text-red-500 transition-colors"
                        aria-label="Remove tag"
                      >
                        <X size={14} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
