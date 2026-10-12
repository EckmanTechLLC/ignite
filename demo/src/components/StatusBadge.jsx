/**
 * Small colored status badge.
 *
 * Preset status values:
 *   0 = Off (gray)
 *   1 = Hand / Manual (yellow)
 *   2 = Auto (green)
 *
 * Or pass a named status string directly.
 *
 * @param {{ status: number | string, className?: string }} props
 */

const PRESETS = {
  // Numeric equipment modes
  0:    { dot: 'bg-slate-400', label: 'Off' },
  1:    { dot: 'bg-amber-500', label: 'Hand' },
  2:    { dot: 'bg-emerald-500', label: 'Auto' },
  // Named states
  ok:       { dot: 'bg-emerald-500', label: 'OK' },
  good:     { dot: 'bg-emerald-500', label: 'Good' },
  warning:  { dot: 'bg-amber-500',   label: 'Warning' },
  alarm:    { dot: 'bg-red-500',     label: 'Alarm' },
  fault:    { dot: 'bg-red-500',     label: 'Fault' },
  offline:  { dot: 'bg-slate-400',   label: 'Offline' },
  online:   { dot: 'bg-emerald-500', label: 'Online' },
  idle:     { dot: 'bg-slate-400',   label: 'Idle' },
  running:  { dot: 'bg-emerald-500', label: 'Running' },
  stopped:  { dot: 'bg-slate-400',   label: 'Stopped' },
};

export default function StatusBadge({ status, className = '' }) {
  const key = status === undefined || status === null ? 'offline' : String(status);
  const preset = PRESETS[key] ?? { dot: 'bg-slate-400', label: key };

  return (
    <span className={`inline-flex items-center gap-1.5 text-sm ${className}`}>
      <span className={`w-2 h-2 rounded-full shrink-0 ${preset.dot}`} />
      <span className="text-slate-700">{preset.label}</span>
    </span>
  );
}
