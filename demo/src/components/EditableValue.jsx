import { useState, useRef, useEffect } from 'react';
import { Pencil } from 'lucide-react';
import { writeTags } from '../api';
import { useAuth } from '../context/AuthContext';

/**
 * Inline editable numeric value. Shows current value with an edit icon.
 * On click: shows an input. On Enter/blur: writes via writeTags().
 * On Escape: cancels.
 *
 * @param {{ path: string, value: number | null | undefined, unit?: string, dec?: number }} props
 */
export default function EditableValue({ path, value, unit = '', dec = 1 }) {
  const { canWrite } = useAuth();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [flash, setFlash] = useState(null); // 'ok' | 'err' | null
  const inputRef = useRef(null);

  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [editing]);

  function startEdit() {
    setDraft(value != null ? Number(value).toFixed(dec) : '');
    setEditing(true);
    setFlash(null);
  }

  function cancel() {
    setEditing(false);
    setDraft('');
  }

  async function commit() {
    const parsed = parseFloat(draft);
    if (isNaN(parsed)) { cancel(); return; }
    setEditing(false);
    try {
      await writeTags([{ path, value: parsed }]);
      setFlash('ok');
    } catch (_) {
      setFlash('err');
    } finally {
      setTimeout(() => setFlash(null), 1200);
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter')  { e.preventDefault(); commit(); }
    if (e.key === 'Escape') { cancel(); }
  }

  const display = value != null ? `${Number(value).toFixed(dec)}${unit ? ' ' + unit : ''}` : '—';

  const flashClass =
    flash === 'ok'  ? 'text-emerald-600' :
    flash === 'err' ? 'text-red-600' :
    '';

  if (editing) {
    return (
      <span className="inline-flex items-center gap-1">
        <input
          ref={inputRef}
          type="number"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={handleKeyDown}
          className="w-20 text-sm font-semibold text-slate-800 border border-blue-400 rounded px-1 py-0.5 focus:outline-none focus:ring-1 focus:ring-blue-400"
        />
        {unit && <span className="text-xs text-slate-400">{unit}</span>}
      </span>
    );
  }

  return (
    <span className={`inline-flex items-center gap-1 group ${flashClass}`}>
      <span className="text-sm font-semibold text-slate-800">{display}</span>
      {canWrite && (
        <button
          onClick={startEdit}
          title="Edit setpoint"
          className="opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity text-slate-400 hover:text-blue-600 focus:text-blue-600 focus:outline-none focus:ring-1 focus:ring-blue-400 rounded"
        >
          <Pencil size={12} />
        </button>
      )}
    </span>
  );
}
