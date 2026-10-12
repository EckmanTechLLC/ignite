import { useState } from 'react';
import { writeTags } from '../api';
import StatusBadge from './StatusBadge';
import { useAuth } from '../context/AuthContext';

/**
 * Clickable HOA badge that cycles Off (0) → Hand (1) → Auto (2) → Off.
 * Writes the new value via writeTags() and shows brief feedback.
 *
 * @param {{ path: string, value: number | null | undefined }} props
 */
export default function HoaToggle({ path, value }) {
  const { canWrite } = useAuth();
  const [writing, setWriting] = useState(false);
  const [flash, setFlash] = useState(null); // 'ok' | 'err' | null

  async function handleClick() {
    if (value == null || writing) return;
    const next = (Number(value) + 1) % 3;
    setWriting(true);
    setFlash(null);
    try {
      await writeTags([{ path, value: next }]);
      setFlash('ok');
    } catch (_) {
      setFlash('err');
    } finally {
      setWriting(false);
      setTimeout(() => setFlash(null), 1200);
    }
  }

  const ring =
    flash === 'ok'  ? 'ring-2 ring-emerald-400' :
    flash === 'err' ? 'ring-2 ring-red-400' :
    '';

  if (!canWrite) {
    return <StatusBadge status={value != null ? Number(value) : undefined} />;
  }

  return (
    <button
      onClick={handleClick}
      disabled={writing || value == null}
      title="Click to cycle HOA mode"
      className={`rounded px-0.5 transition-all cursor-pointer disabled:cursor-default ${ring} ${writing ? 'opacity-60' : 'hover:bg-slate-100'}`}
    >
      <StatusBadge status={value != null ? Number(value) : undefined} />
    </button>
  );
}
