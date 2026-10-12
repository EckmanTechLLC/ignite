/**
 * Inline live tag value display with quality indicator.
 *
 * @param {{
 *   path: string,
 *   values: Object,     // { [path]: { value, quality, timestamp } }
 *   unit?: string,
 *   decimals?: number,
 *   className?: string,
 * }} props
 */
export default function TagValue({ path, values = {}, unit, decimals, className = '' }) {
  const entry = values[path];

  if (!entry) {
    return (
      <span className={`inline-flex items-center gap-1 text-slate-400 ${className}`}>
        <span className="w-1.5 h-1.5 rounded-full bg-slate-300 shrink-0" />
        <span>—</span>
      </span>
    );
  }

  const { value, quality } = entry;

  // Quality: >= 192 is Good in Ignition OPC-UA quality codes
  const isGood = quality == null || Number(quality) >= 192;
  const dotColor = isGood ? 'bg-emerald-500' : 'bg-amber-500';

  let display;
  if (typeof value === 'number') {
    display = decimals != null ? value.toFixed(decimals) : String(value);
  } else if (value === null || value === undefined) {
    display = '—';
  } else {
    display = String(value);
  }

  return (
    <span className={`inline-flex items-center gap-1 ${className}`}>
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${dotColor}`} />
      <span className="text-slate-900 font-medium">{display}</span>
      {unit && <span className="text-slate-500 text-sm">{unit}</span>}
    </span>
  );
}
