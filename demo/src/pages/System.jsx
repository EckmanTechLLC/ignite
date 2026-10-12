import { useEffect, useState } from 'react';
import { Server, Database, Package, Info, ChevronRight, ChevronDown, RefreshCw, Terminal, Play, Lock } from 'lucide-react';
import { getSystemInfo, getDbConnections, browseTags, readTags, getProjects, executeLibraryScript, executeSystemFunction } from '../api';
import { useAuth } from '../context/AuthContext';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatUptime(ms) {
  if (ms == null || ms < 0) return '—';
  const totalSec = Math.floor(ms / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const parts = [];
  if (d > 0) parts.push(`${d}d`);
  if (h > 0) parts.push(`${h}h`);
  parts.push(`${m}m`);
  return parts.join(' ');
}

// ---------------------------------------------------------------------------
// Gateway Info Card
// ---------------------------------------------------------------------------

function GatewayInfoCard({ info }) {
  if (!info) {
    return (
      <div className="bg-white rounded-xl shadow-sm p-5 flex items-center justify-center min-h-[160px]">
        <RefreshCw size={18} className="text-slate-400 animate-spin" />
      </div>
    );
  }

  const rows = [
    { label: 'Gateway Name', value: info.gatewayName },
    { label: 'Uptime',       value: formatUptime(info.uptimeMs) },
    { label: 'JVM',          value: `${info.jvmVersion} (${info.jvmVendor})` },
    { label: 'OS',           value: `${info.os} / ${info.arch}` },
    { label: 'Timezone',     value: info.timezone },
  ];

  return (
    <div className="bg-white rounded-xl shadow-sm p-5 flex gap-4">
      <div className="shrink-0 w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
        <Server size={20} className="text-blue-600" />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">
          Gateway Info
        </p>
        <dl className="space-y-1.5">
          {rows.map(({ label, value }) => (
            <div key={label} className="flex gap-2 text-sm">
              <dt className="text-slate-500 w-32 shrink-0">{label}</dt>
              <dd className="text-slate-800 font-medium truncate">{value ?? '—'}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Quick Stats
// ---------------------------------------------------------------------------

function QuickStat({ icon: Icon, label, value, color = 'text-blue-600', bg = 'bg-blue-50' }) {
  return (
    <div className="bg-white rounded-xl shadow-sm p-4 flex items-center gap-3">
      <div className={`shrink-0 w-9 h-9 rounded-lg ${bg} flex items-center justify-center`}>
        <Icon size={18} className={color} />
      </div>
      <div className="min-w-0">
        <p className="text-xs text-slate-500">{label}</p>
        <p className="text-lg font-bold text-slate-800 leading-tight">{value ?? '—'}</p>
      </div>
    </div>
  );
}

function QuickStats({ info, dbCount }) {
  const igniteVersion = info?.modules?.find(
    (m) => m.name?.toLowerCase().includes('ignite')
  )?.version ?? '—';

  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
      <QuickStat
        icon={Package}
        label="Modules"
        value={info?.moduleCount ?? '—'}
        color="text-violet-600"
        bg="bg-violet-50"
      />
      <QuickStat
        icon={Database}
        label="DB Connections"
        value={dbCount ?? '—'}
        color="text-emerald-600"
        bg="bg-emerald-50"
      />
      <QuickStat
        icon={Server}
        label="Ignite Version"
        value={igniteVersion}
        color="text-blue-600"
        bg="bg-blue-50"
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Modules Table
// ---------------------------------------------------------------------------

function ModulesTable({ modules }) {
  if (!modules) {
    return (
      <div className="flex items-center justify-center py-8 text-slate-400 text-sm gap-2">
        <RefreshCw size={14} className="animate-spin" /> Loading…
      </div>
    );
  }

  const sorted = [...modules].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? '')
  );

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm text-left">
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50">
            <th className="px-4 py-2.5 font-semibold text-slate-500">Module</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">ID</th>
            <th className="px-4 py-2.5 font-semibold text-slate-500">Version</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((mod, i) => {
            const isIgnite = mod.name?.toLowerCase().includes('ignite');
            return (
              <tr
                key={mod.id ?? i}
                className={`border-b border-slate-100 transition-colors ${
                  isIgnite ? 'bg-blue-50 hover:bg-blue-100' : 'hover:bg-slate-50'
                }`}
              >
                <td className={`px-4 py-2.5 font-medium ${isIgnite ? 'text-blue-800' : 'text-slate-700'}`}>
                  {mod.name ?? '—'}
                  {isIgnite && (
                    <span className="ml-2 text-[10px] font-semibold bg-blue-200 text-blue-700 px-1.5 py-0.5 rounded">
                      THIS
                    </span>
                  )}
                </td>
                <td className="px-4 py-2.5 font-mono text-xs text-slate-500">{mod.id ?? '—'}</td>
                <td className="px-4 py-2.5 text-slate-600">{mod.version ?? '—'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tag Browser
// ---------------------------------------------------------------------------

function TagNode({ node, depth = 0, onSelect, selectedPath }) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState(null);
  const [loading, setLoading]   = useState(false);

  const isLeaf    = !node.hasChildren;
  const isSelected = selectedPath === node.path;

  async function handleClick() {
    if (isLeaf) {
      onSelect(node);
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
        onClick={handleClick}
        className={`w-full flex items-center gap-1.5 px-2 py-1 rounded text-sm text-left
          transition-colors hover:bg-slate-100
          ${isSelected ? 'text-blue-600 bg-blue-50 font-medium' : 'text-slate-700'}`}
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
              depth={depth + 1}
              onSelect={onSelect}
              selectedPath={selectedPath}
            />
          ))}
          {children.length === 0 && (
            <p
              className="text-xs text-slate-400 py-1 px-2"
              style={{ paddingLeft: `${0.5 + (depth + 1) * 1}rem` }}
            >
              Empty
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function TagBrowser() {
  const [roots, setRoots]               = useState(null);
  const [filter, setFilter]             = useState('');
  const [selectedNode, setSelectedNode] = useState(null);
  const [tagDetail, setTagDetail]       = useState(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

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

  async function handleSelect(node) {
    setSelectedNode(node);
    setTagDetail(null);
    if (!node.path) return;
    setLoadingDetail(true);
    try {
      const data = await readTags([node.path]);
      const results = Array.isArray(data) ? data : Array.isArray(data?.tags) ? data.tags : Array.isArray(data?.results) ? data.results : [];
      setTagDetail(results[0] ?? null);
    } catch (_) {
      setTagDetail(null);
    } finally {
      setLoadingDetail(false);
    }
  }

  const visible = roots
    ? filter
      ? roots.filter((n) => n.name?.toLowerCase().includes(filter.toLowerCase()))
      : roots
    : null;

  return (
    <div className="flex flex-col h-full gap-3">
      {/* Search */}
      <div className="px-1">
        <input
          type="text"
          placeholder="Filter tags…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="w-full px-2.5 py-1.5 text-xs border border-slate-200 rounded
                     focus:outline-none focus:ring-1 focus:ring-blue-400"
        />
      </div>

      {/* Tree */}
      <div className="flex-1 overflow-y-auto">
        {visible === null ? (
          <p className="text-xs text-slate-400 p-2 flex items-center gap-1">
            <RefreshCw size={11} className="animate-spin" /> Loading…
          </p>
        ) : visible.length === 0 ? (
          <p className="text-xs text-slate-400 p-2">No tags found</p>
        ) : (
          visible.map((node, i) => (
            <TagNode
              key={node.path ?? node.name ?? i}
              node={node}
              onSelect={handleSelect}
              selectedPath={selectedNode?.path}
            />
          ))
        )}
      </div>

      {/* Tag detail */}
      {selectedNode && (
        <div className="border-t border-slate-200 pt-3 text-xs space-y-1.5">
          <p className="font-semibold text-slate-600 mb-1">Tag Detail</p>
          <div className="flex gap-1">
            <span className="text-slate-400 w-16 shrink-0">Path</span>
            <span className="text-slate-700 font-mono break-all">{selectedNode.path ?? '—'}</span>
          </div>
          {loadingDetail ? (
            <p className="text-slate-400 flex items-center gap-1">
              <RefreshCw size={10} className="animate-spin" /> Reading…
            </p>
          ) : tagDetail ? (
            <>
              <div className="flex gap-1">
                <span className="text-slate-400 w-16 shrink-0">Value</span>
                <span className="text-slate-800 font-semibold">
                  {tagDetail.value != null ? String(tagDetail.value) : '—'}
                </span>
              </div>
              <div className="flex gap-1">
                <span className="text-slate-400 w-16 shrink-0">Quality</span>
                <span className="text-slate-700">{tagDetail.quality ?? '—'}</span>
              </div>
              {tagDetail.dataType && (
                <div className="flex gap-1">
                  <span className="text-slate-400 w-16 shrink-0">Type</span>
                  <span className="text-slate-700">{tagDetail.dataType}</span>
                </div>
              )}
              {tagDetail.timestamp && (
                <div className="flex gap-1">
                  <span className="text-slate-400 w-16 shrink-0">Updated</span>
                  <span className="text-slate-500">
                    {new Date(tagDetail.timestamp).toLocaleTimeString()}
                  </span>
                </div>
              )}
            </>
          ) : null}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// DB Connections
// ---------------------------------------------------------------------------

function DbConnections({ connections }) {
  if (!connections) {
    return (
      <div className="flex items-center justify-center py-8 text-slate-400 text-sm gap-2">
        <RefreshCw size={14} className="animate-spin" />
      </div>
    );
  }

  if (connections.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-8 gap-2 text-slate-400">
        <Info size={24} />
        <p className="text-sm">No database connections configured</p>
      </div>
    );
  }

  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b border-slate-200 bg-slate-50">
          <th className="px-3 py-2 text-left font-semibold text-slate-500">Name</th>
          <th className="px-3 py-2 text-left font-semibold text-slate-500">Status</th>
          <th className="px-3 py-2 text-right font-semibold text-slate-500">Active</th>
        </tr>
      </thead>
      <tbody>
        {connections.map((conn, i) => {
          const statusOk = conn.status?.toLowerCase() === 'valid'
            || conn.status?.toLowerCase() === 'connected';
          return (
            <tr key={conn.name ?? i} className="border-b border-slate-100 hover:bg-slate-50">
              <td className="px-3 py-2 text-slate-700 font-medium">{conn.name ?? '—'}</td>
              <td className="px-3 py-2">
                <span
                  className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                    statusOk
                      ? 'bg-emerald-100 text-emerald-700'
                      : conn.problem
                      ? 'bg-red-100 text-red-700'
                      : 'bg-slate-100 text-slate-600'
                  }`}
                  title={conn.problem ?? undefined}
                >
                  {conn.status ?? 'unknown'}
                </span>
              </td>
              <td className="px-3 py-2 text-right text-slate-500 text-xs">
                {conn.activeConnections ?? 0} / {conn.maxConnections ?? '—'}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

// ---------------------------------------------------------------------------
// Script Console
// ---------------------------------------------------------------------------

const QUICK_ACTIONS = [
  {
    label: 'Hello World',
    mode: 'library',
    path: 'demo.helloWorld',
    args: '[]',
    title: 'Calls demo.helloWorld() — the test library function',
  },
  {
    label: 'Gateway Status',
    mode: 'system',
    path: 'system.util.getGatewayStatus',
    args: '[]',
    title: 'Calls system.util.getGatewayStatus()',
  },
  {
    label: 'Read Tag',
    mode: 'system',
    path: 'system.tag.readBlocking',
    args: '[["[default]MyTag/Value"], 30000]',
    title: 'Calls system.tag.readBlocking with a sample tag path',
  },
  {
    label: 'Named Query',
    mode: 'system',
    path: 'system.db.runNamedQuery',
    args: '["MyQuery", {}]',
    title: 'Calls system.db.runNamedQuery',
  },
];

function ScriptConsole() {
  const [mode, setMode]         = useState('library');
  const [project, setProject]   = useState('');
  const [projects, setProjects] = useState([]);
  const [path, setPath]         = useState('');
  const [argsText, setArgsText] = useState('[]');
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);
  const [history, setHistory]   = useState([]); // [{mode, path, args, result, error, ts}]

  useEffect(() => {
    getProjects()
      .then((data) => {
        const list = Array.isArray(data) ? data : Array.isArray(data?.projects) ? data.projects : [];
        setProjects(list);
        if (list.length > 0) setProject(list[0]);
      })
      .catch(() => setProjects([]));
  }, []);

  async function execute() {
    let parsedArgs;
    try {
      parsedArgs = JSON.parse(argsText || '[]');
      if (!Array.isArray(parsedArgs)) throw new Error('Args must be a JSON array');
    } catch (e) {
      setError(`Invalid args JSON: ${e.message}`);
      return;
    }

    setLoading(true);
    setError(null);

    const entry = { mode, path, args: parsedArgs, ts: Date.now() };
    try {
      let result;
      if (mode === 'library') {
        result = await executeLibraryScript(project, path, parsedArgs);
      } else {
        result = await executeSystemFunction(project, path, parsedArgs);
      }
      entry.result = result;
      setError(null);
    } catch (e) {
      entry.error = e.message;
      setError(e.message);
    } finally {
      setLoading(false);
    }

    setHistory((prev) => [entry, ...prev].slice(0, 10));
  }

  function applyQuickAction(qa) {
    setMode(qa.mode);
    setPath(qa.path);
    setArgsText(qa.args);
  }

  const latestEntry = history[0];

  return (
    <div className="bg-white rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2">
        <Terminal size={15} className="text-slate-500" />
        <p className="text-sm font-semibold text-slate-700">Script Console</p>
        <span className="ml-auto text-xs text-slate-400">
          Calls Ignition project library and system.* functions
        </span>
      </div>

      <div className="p-4 space-y-4">

        {/* Mode tabs */}
        <div className="flex gap-1 p-1 bg-slate-100 rounded-lg w-fit">
          {['library', 'system'].map((m) => (
            <button
              key={m}
              onClick={() => setMode(m)}
              className={`px-3 py-1 rounded-md text-xs font-semibold transition-colors capitalize ${
                mode === m
                  ? 'bg-white shadow-sm text-slate-800'
                  : 'text-slate-500 hover:text-slate-700'
              }`}
            >
              {m === 'library' ? 'Library' : 'System.*'}
            </button>
          ))}
        </div>

        {/* Project + path row */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {/* Project selector */}
          <div>
            <label className="block text-xs font-medium text-slate-500 mb-1">Project</label>
            {projects.length > 0 ? (
              <select
                value={project}
                onChange={(e) => setProject(e.target.value)}
                className="w-full px-2.5 py-1.5 text-sm border border-slate-200 rounded-lg
                           focus:outline-none focus:ring-1 focus:ring-blue-400 bg-white"
              >
                {projects.map((p) => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            ) : (
              <div className="px-2.5 py-1.5 text-sm text-slate-400 border border-slate-200 rounded-lg bg-slate-50">
                No projects
              </div>
            )}
          </div>

          {/* Function path */}
          <div className="sm:col-span-2">
            <label className="block text-xs font-medium text-slate-500 mb-1">
              {mode === 'library' ? 'Library path (e.g. demo.helloWorld)' : 'Function (e.g. system.tag.readBlocking)'}
            </label>
            <input
              type="text"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && execute()}
              placeholder={mode === 'library' ? 'demo.helloWorld' : 'system.tag.readBlocking'}
              className="w-full px-2.5 py-1.5 text-sm font-mono border border-slate-200 rounded-lg
                         focus:outline-none focus:ring-1 focus:ring-blue-400"
            />
          </div>
        </div>

        {/* Args */}
        <div>
          <label className="block text-xs font-medium text-slate-500 mb-1">
            Args (JSON array)
          </label>
          <textarea
            rows={2}
            value={argsText}
            onChange={(e) => setArgsText(e.target.value)}
            placeholder='e.g. ["arg1", 42]'
            className="w-full px-2.5 py-1.5 text-sm font-mono border border-slate-200 rounded-lg
                       focus:outline-none focus:ring-1 focus:ring-blue-400 resize-none"
          />
        </div>

        {/* Execute button */}
        <button
          onClick={execute}
          disabled={loading || !path.trim()}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-semibold
                     rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed
                     transition-colors"
        >
          {loading ? (
            <RefreshCw size={14} className="animate-spin" />
          ) : (
            <Play size={14} />
          )}
          Execute
        </button>

        {/* Quick actions */}
        <div>
          <p className="text-xs font-medium text-slate-500 mb-2">Quick actions</p>
          <div className="flex flex-wrap gap-2">
            {QUICK_ACTIONS.map((qa) => (
              <button
                key={qa.label}
                title={qa.title}
                onClick={() => {
                  applyQuickAction(qa);
                  // small delay so state updates before execute
                  setTimeout(() => {
                    setMode(qa.mode);
                    setPath(qa.path);
                    setArgsText(qa.args);
                  }, 0);
                }}
                className="px-3 py-1.5 text-xs font-medium bg-slate-100 text-slate-700
                           rounded-lg hover:bg-slate-200 transition-colors"
              >
                {qa.label}
              </button>
            ))}
          </div>
        </div>

        {/* Result / Error */}
        {latestEntry && (
          <div>
            <p className="text-xs font-medium text-slate-500 mb-1">
              Result —{' '}
              <span className="font-mono text-slate-400">{latestEntry.path}</span>
              <span className="ml-2 text-slate-400">{new Date(latestEntry.ts).toLocaleTimeString()}</span>
            </p>
            {latestEntry.error ? (
              <div className="bg-red-50 text-red-700 rounded-lg p-3 text-sm">
                {latestEntry.error}
              </div>
            ) : (
              <pre className="bg-slate-50 rounded-lg p-4 text-xs overflow-auto max-h-64 text-slate-800">
                {JSON.stringify(latestEntry.result, null, 2)}
              </pre>
            )}
          </div>
        )}

        {/* Inline error for parse issues */}
        {error && !latestEntry?.error && (
          <div className="bg-red-50 text-red-700 rounded-lg p-3 text-sm">{error}</div>
        )}

        {/* Execution history */}
        {history.length > 1 && (
          <details className="group">
            <summary className="cursor-pointer text-xs font-medium text-slate-500 hover:text-slate-700 select-none">
              History ({history.length - 1} previous)
            </summary>
            <div className="mt-2 space-y-2">
              {history.slice(1).map((entry) => (
                <div
                  key={entry.ts}
                  className="border border-slate-100 rounded-lg p-3 cursor-pointer hover:border-slate-300 transition-colors"
                  onClick={() => { setMode(entry.mode); setPath(entry.path); setArgsText(JSON.stringify(entry.args)); }}
                >
                  <div className="flex items-center gap-2 mb-1">
                    <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded ${
                      entry.error
                        ? 'bg-red-100 text-red-600'
                        : 'bg-emerald-100 text-emerald-700'
                    }`}>
                      {entry.error ? 'error' : 'ok'}
                    </span>
                    <span className="text-xs font-mono text-slate-600 truncate">{entry.path}</span>
                    <span className="ml-auto text-xs text-slate-400 shrink-0">
                      {new Date(entry.ts).toLocaleTimeString()}
                    </span>
                  </div>
                  {entry.error ? (
                    <p className="text-xs text-red-600 truncate">{entry.error}</p>
                  ) : (
                    <pre className="text-xs text-slate-500 truncate">
                      {JSON.stringify(entry.result).slice(0, 120)}
                    </pre>
                  )}
                </div>
              ))}
            </div>
          </details>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function System() {
  const { canWrite } = useAuth();
  const [systemInfo, setSystemInfo]     = useState(null);
  const [connections, setConnections]   = useState(null);

  useEffect(() => {
    getSystemInfo()
      .then(setSystemInfo)
      .catch(() => setSystemInfo({}));

    getDbConnections()
      .then((data) => setConnections(Array.isArray(data?.connections) ? data.connections : []))
      .catch(() => setConnections([]));
  }, []);

  return (
    <div className="space-y-5">

      {/* Row 1: Gateway Info + Quick Stats */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <GatewayInfoCard info={systemInfo} />
        <div className="flex flex-col justify-center">
          <QuickStats info={systemInfo} dbCount={connections?.length} />
        </div>
      </div>

      {/* Row 2: Modules Table */}
      <div className="bg-white rounded-xl shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2">
          <Package size={15} className="text-slate-500" />
          <p className="text-sm font-semibold text-slate-700">Installed Modules</p>
          {systemInfo?.moduleCount != null && (
            <span className="ml-auto text-xs text-slate-400">{systemInfo.moduleCount} modules</span>
          )}
        </div>
        <ModulesTable modules={systemInfo?.modules} />
      </div>

      {/* Row 3: Tag Browser + DB Connections */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">

        {/* Tag Browser */}
        <div className="bg-white rounded-xl shadow-sm overflow-hidden flex flex-col" style={{ maxHeight: 480 }}>
          <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2 shrink-0">
            <ChevronRight size={15} className="text-slate-500" />
            <p className="text-sm font-semibold text-slate-700">Tag Browser</p>
          </div>
          <div className="flex-1 overflow-hidden p-3">
            <TagBrowser />
          </div>
        </div>

        {/* DB Connections */}
        <div className="bg-white rounded-xl shadow-sm overflow-hidden">
          <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2">
            <Database size={15} className="text-slate-500" />
            <p className="text-sm font-semibold text-slate-700">Database Connections</p>
            {connections != null && (
              <span className="ml-auto text-xs text-slate-400">{connections.length} configured</span>
            )}
          </div>
          <DbConnections connections={connections} />
        </div>
      </div>

      {/* Row 4: Script Console (write access required) */}
      {canWrite ? (
        <ScriptConsole />
      ) : (
        <div className="bg-white rounded-xl shadow-sm overflow-hidden">
          <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2">
            <Terminal size={15} className="text-slate-500" />
            <p className="text-sm font-semibold text-slate-700">Script Console</p>
          </div>
          <div className="flex items-center justify-center gap-2 py-10 text-slate-400">
            <Lock size={16} />
            <span className="text-sm">Requires write access</span>
          </div>
        </div>
      )}

    </div>
  );
}
