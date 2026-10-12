/**
 * KPI summary card.
 *
 * @param {{
 *   icon: React.ComponentType,
 *   label: string,
 *   value: string | number,
 *   unit?: string,
 *   colorClass?: string,   // Tailwind bg color class, e.g. 'bg-emerald-50'
 *   iconClass?: string,    // Tailwind text color for icon, e.g. 'text-emerald-600'
 * }} props
 */
export default function KpiCard({
  icon: Icon,
  label,
  value,
  unit,
  colorClass = 'bg-white',
  iconClass = 'text-blue-600',
}) {
  return (
    <div className={`${colorClass} rounded-xl shadow-sm p-6 flex items-start gap-4`}>
      {Icon && (
        <div className={`shrink-0 ${iconClass}`}>
          <Icon size={24} />
        </div>
      )}
      <div className="min-w-0">
        <p className="text-sm text-slate-500 font-medium truncate">{label}</p>
        <p className="text-2xl font-bold text-slate-900 leading-tight mt-0.5">
          {value ?? '—'}
          {unit && (
            <span className="text-base font-normal text-slate-500 ml-1">{unit}</span>
          )}
        </p>
      </div>
    </div>
  );
}
