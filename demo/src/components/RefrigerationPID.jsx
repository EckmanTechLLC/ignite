/**
 * Refrigeration P&ID — SCADA-style SVG with live tag overlays.
 * Dark background, animated flow, industry-standard equipment symbols.
 */

// ─── helpers ──────────────────────────────────────────────────────────────────

function v(values, path) {
  return values[path]?.value ?? null;
}

function fmt(val, dec = 1) {
  if (val == null) return '—';
  return Number(val).toFixed(dec);
}

function hoaColor(hoa) {
  if (hoa == null) return '#475569';
  switch (Number(hoa)) {
    case 0: return '#475569';
    case 1: return '#f59e0b';
    case 2: return '#22c55e';
    default: return '#475569';
  }
}

function hoaLabel(hoa) {
  if (hoa == null) return 'Offline';
  return ['Off', 'Hand', 'Auto'][Number(hoa)] ?? '—';
}

function valveColor(pos) {
  if (pos == null) return '#f59e0b';
  const p = Number(pos);
  if (p >= 90) return '#22c55e';
  if (p <= 10) return '#ef4444';
  return '#f59e0b';
}

// ─── instrument tag callout ───────────────────────────────────────────────────

function DataTag({ x, y, lines }) {
  const w = 92;
  const h = 14 * lines.length + 8;
  return (
    <g>
      <rect x={x - w / 2} y={y} width={w} height={h} rx={3}
        fill="#0a1628" stroke="#334155" strokeWidth={1} opacity={0.92} />
      {lines.map((line, i) => (
        <text key={i} x={x} y={y + 11 + i * 14}
          textAnchor="middle" fontSize={9} fill="#94a3b8">{line}</text>
      ))}
    </g>
  );
}

// ─── Compressor: circle + diagonal symbol ─────────────────────────────────────

function CompressorSymbol({ cx, cy, r, num, values, selected, onClick }) {
  const base = `[default]Refrigeration/Compressor${num}`;
  const g    = (f) => v(values, `${base}/${f}`);

  const hoa   = g('hoa');
  const amps  = g('motorAmps');
  const suctP = g('pressureSuction');
  const discP = g('pressureDischarge');
  const color   = hoaColor(hoa);
  const running = hoa != null && Number(hoa) > 0;

  return (
    <g onClick={onClick} style={{ cursor: 'pointer' }} role="button" aria-label={`Compressor ${num}`}>
      {/* selection glow */}
      {selected && (
        <circle cx={cx} cy={cy} r={r + 11} fill="none" stroke="#818cf8"
          strokeWidth={2} opacity={0.65} filter="url(#glow-sel)" />
      )}
      {/* HOA status ring */}
      <circle cx={cx} cy={cy} r={r + 5} fill="none" stroke={color}
        strokeWidth={1.5} opacity={0.4} />
      {/* animated body group */}
      <g className={running ? 'compressor-running' : ''}
         style={{ transformBox: 'fill-box', transformOrigin: 'center' }}>
        <circle cx={cx} cy={cy} r={r} fill="#071528" stroke={color} strokeWidth={2.5} />
        {/* diagonal — industry compressor symbol */}
        <line x1={cx - r * 0.55} y1={cy + r * 0.55}
              x2={cx + r * 0.55} y2={cy - r * 0.55}
          stroke={color} strokeWidth={2} />
        {/* small terminal nubs */}
        <circle cx={cx - r * 0.55} cy={cy + r * 0.55} r={3} fill={color} />
        <circle cx={cx + r * 0.55} cy={cy - r * 0.55} r={3} fill={color} />
      </g>
      {/* C# inside */}
      <text x={cx + 7} y={cy - 2} textAnchor="middle" fontSize={9}
        fontWeight="700" fill={color} opacity={0.75}>C{num}</text>
      {/* title + HOA above */}
      <text x={cx} y={cy - r - 18} textAnchor="middle" fontSize={11}
        fontWeight="700" fill="#e2e8f0">Compressor {num}</text>
      <text x={cx} y={cy - r - 6} textAnchor="middle" fontSize={9} fill={color}>
        {hoaLabel(hoa)}
      </text>
      {/* motor amps below */}
      <text x={cx} y={cy + r + 18} textAnchor="middle" fontSize={16}
        fontWeight="800" fill="#f1f5f9">{fmt(amps, 1)}</text>
      <text x={cx} y={cy + r + 30} textAnchor="middle" fontSize={8} fill="#64748b">
        A motor
      </text>
      {/* port pressure labels */}
      <text x={cx - r - 6} y={cy + 4} textAnchor="end" fontSize={8} fill="#7dd3fc">
        S {fmt(suctP, 1)}
      </text>
      <text x={cx + r + 6} y={cy + 4} fontSize={8} fill="#fca5a5">
        D {fmt(discP, 1)}
      </text>
    </g>
  );
}

// ─── Cooling Tower: trapezoid + fan ───────────────────────────────────────────

function TowerSymbol({ x, y, w, h, num, values, selected, onClick }) {
  const base = `[default]Refrigeration/Tower${num}`;
  const g    = (f) => v(values, `${base}/${f}`);

  const hz      = g('fanSpeed');
  const temp    = g('temp');
  const ph      = g('pH');
  const running = hz != null && Number(hz) > 0;
  const color   = running ? '#00d4ff' : '#475569';

  const cx     = x + w / 2;
  const offset = 14;
  const trap   = `${x},${y} ${x + w},${y} ${x + w - offset},${y + h} ${x + offset},${y + h}`;

  // fan inside, near top
  const fanCX = cx;
  const fanCY = y + 22;
  const fanR  = 13;

  return (
    <g onClick={onClick} style={{ cursor: 'pointer' }} role="button" aria-label={`Tower ${num}`}>
      {selected && (
        <polygon
          points={`${x-4},${y-4} ${x+w+4},${y-4} ${x+w-offset+4},${y+h+4} ${x+offset-4},${y+h+4}`}
          fill="none" stroke="#818cf8" strokeWidth={2} opacity={0.65} />
      )}
      {/* tower body */}
      <polygon points={trap} fill="#071528" stroke={color} strokeWidth={2} />
      {/* water cascade lines */}
      {[0.5, 0.65, 0.8].map((frac) => {
        const wy   = y + h * frac;
        const wl   = x + offset * frac + 6;
        const wr   = x + w - offset * frac - 6;
        return (
          <line key={frac} x1={wl} y1={wy} x2={wr} y2={wy}
            stroke={color} strokeWidth={1} opacity={0.22} strokeDasharray="3 4" />
        );
      })}
      {/* fan circle */}
      <circle cx={fanCX} cy={fanCY} r={fanR}
        fill="#071528" stroke={color} strokeWidth={1.5} />
      {/* fan blades — animated group */}
      <g className={running ? 'fan-spinning' : ''}
         style={{ transformBox: 'fill-box', transformOrigin: `${fanCX}px ${fanCY}px` }}>
        <line x1={fanCX - fanR + 2} y1={fanCY}
              x2={fanCX + fanR - 2} y2={fanCY}
          stroke={color} strokeWidth={1.5} />
        <line x1={fanCX} y1={fanCY - fanR + 2}
              x2={fanCX} y2={fanCY + fanR - 2}
          stroke={color} strokeWidth={1.5} />
        <line x1={fanCX - 8} y1={fanCY - 8} x2={fanCX + 8} y2={fanCY + 8}
          stroke={color} strokeWidth={1} opacity={0.65} />
        <line x1={fanCX + 8} y1={fanCY - 8} x2={fanCX - 8} y2={fanCY + 8}
          stroke={color} strokeWidth={1} opacity={0.65} />
      </g>
      {/* tower label inside (bottom) */}
      <text x={cx} y={y + h - 10} textAnchor="middle" fontSize={10}
        fontWeight="700" fill="#94a3b8">Tower {num}</text>
      {/* fan speed below */}
      <text x={cx} y={y + h + 17} textAnchor="middle" fontSize={14}
        fontWeight="800" fill="#f1f5f9">{fmt(hz, 0)}</text>
      <text x={cx} y={y + h + 28} textAnchor="middle" fontSize={8} fill="#64748b">
        Hz fan
      </text>
      <text x={cx} y={y + h + 41} textAnchor="middle" fontSize={9} fill="#94a3b8">
        {fmt(temp, 1)}°F · pH {fmt(ph, 2)}
      </text>
    </g>
  );
}

// ─── Vessel: horizontal cylinder with liquid fill ─────────────────────────────

function VesselSymbol({ cx, cy, w, h, label, level, fillColor, selected, onClick }) {
  const x    = cx - w / 2;
  const y    = cy - h / 2;
  const rx   = h / 2;
  const pct  = level != null ? Math.min(100, Math.max(0, Number(level))) : 0;
  const fillW = Math.max(0, (pct / 100) * (w - 4));
  const clipId = `cv-${label.replace(/\s+/g, '')}`;
  const gradId = `gv-${label.replace(/\s+/g, '')}`;
  const stroke = selected ? '#818cf8' : '#334155';
  const strokeW = selected ? 2.5 : 1.5;

  return (
    <g onClick={onClick} style={{ cursor: 'pointer' }} role="button" aria-label={label}>
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%"   stopColor={fillColor} stopOpacity="0.25" />
          <stop offset="100%" stopColor={fillColor} stopOpacity="0.7"  />
        </linearGradient>
        <clipPath id={clipId}>
          <rect x={x + 2} y={y + 2} width={w - 4} height={h - 4} rx={rx - 2} />
        </clipPath>
      </defs>
      {/* vessel body */}
      <rect x={x} y={y} width={w} height={h} rx={rx}
        fill="#071528" stroke={stroke} strokeWidth={strokeW} />
      {/* dished-end ellipses */}
      <ellipse cx={x + rx} cy={cy} rx={rx * 0.32} ry={h / 2 - 1}
        fill="none" stroke={stroke} strokeWidth={1} opacity={0.4} />
      <ellipse cx={x + w - rx} cy={cy} rx={rx * 0.32} ry={h / 2 - 1}
        fill="none" stroke={stroke} strokeWidth={1} opacity={0.4} />
      {/* liquid fill */}
      <rect x={x + 2} y={y + 2} width={fillW} height={h - 4}
        fill={`url(#${gradId})`} clipPath={`url(#${clipId})`}
        style={{ transition: 'width 0.5s ease' }} />
      {/* label */}
      <text x={cx} y={y - 10} textAnchor="middle" fontSize={10}
        fontWeight="700" fill="#e2e8f0">{label}</text>
      {/* level % */}
      <text x={cx} y={cy + 5} textAnchor="middle" fontSize={14}
        fontWeight="800" fill="#f1f5f9">{fmt(level, 0)}%</text>
    </g>
  );
}

// ─── Valve: bowtie symbol ─────────────────────────────────────────────────────

function ValveSymbol({ cx, cy, size = 13, label, position, selected, onClick }) {
  const color = valveColor(position);
  const s     = size;

  return (
    <g onClick={onClick} style={{ cursor: 'pointer' }} role="button" aria-label={label}>
      {selected && (
        <circle cx={cx} cy={cy} r={s + 7} fill="none" stroke="#818cf8"
          strokeWidth={2} opacity={0.65} />
      )}
      {/* left triangle */}
      <polygon points={`${cx - s},${cy - s} ${cx},${cy} ${cx - s},${cy + s}`}
        fill={color} opacity={0.85} />
      {/* right triangle */}
      <polygon points={`${cx + s},${cy - s} ${cx},${cy} ${cx + s},${cy + s}`}
        fill={color} opacity={0.85} />
      {/* outline */}
      <polygon points={`${cx - s},${cy - s} ${cx},${cy} ${cx - s},${cy + s}`}
        fill="none" stroke={color} strokeWidth={0.75} />
      <polygon points={`${cx + s},${cy - s} ${cx},${cy} ${cx + s},${cy + s}`}
        fill="none" stroke={color} strokeWidth={0.75} />
      {/* center pin */}
      <circle cx={cx} cy={cy} r={2} fill={color} />
      {/* position */}
      <text x={cx + s + 8} y={cy + 4} fontSize={9} fill={color}>
        {fmt(position, 0)}%
      </text>
      {/* label below */}
      <text x={cx} y={cy + s + 14} textAnchor="middle" fontSize={8} fill="#64748b">
        {label}
      </text>
    </g>
  );
}

// ─── main component ───────────────────────────────────────────────────────────

export default function RefrigerationPID({ values, onSelectEquipment, selectedEquipment }) {
  const sel = selectedEquipment;

  function toggle(eq) {
    const isSame = eq.type === sel?.type && (eq.num == null || eq.num === sel?.num);
    onSelectEquipment(isSame ? null : eq);
  }

  // ── layout ────────────────────────────────────────────────────────────────
  const C1 = { cx: 120, cy: 200, r: 42 };
  const C2 = { cx: 120, cy: 468, r: 42 };
  const T1 = { x: 845, y: 65,  w: 120, h: 88 };
  const T2 = { x: 845, y: 215, w: 120, h: 88 };
  const RV = { cx: 916, cy: 440, w: 140, h: 56 };
  const AC = { cx: 400, cy: 558, w: 120, h: 56 };
  const KV = { cx: 650, cy: 558 };
  const DV = { cx: 916, cy: 505 };

  // pipe key lines
  const HDR_Y = 38;
  const SUC_Y = 626;
  const LC_X  = 210;
  const RC_X  = 975;
  const CON_X = 828; // condenser collect vertical x

  // pipe attachment points
  const c1Right  = C1.cx + C1.r;           // 162
  const c2Right  = C2.cx + C2.r;
  const c1Bot    = C1.cy + C1.r;           // 242
  const c2Bot    = C2.cy + C2.r;           // 510
  const t1InY    = T1.y + T1.h * 0.35;    // ~96  (hot gas inlet, right of tower)
  const t2InY    = T2.y + T2.h * 0.35;    // ~246
  const t1OutY   = T1.y + T1.h * 0.65;    // ~122 (cond outlet, left of tower)
  const t2OutY   = T2.y + T2.h * 0.65;    // ~272
  const rvLeftX  = RV.cx - RV.w / 2;      // 846
  const rvBotY   = RV.cy + RV.h / 2;      // 468
  const acRightX = AC.cx + AC.w / 2;      // 460
  const acBotY   = AC.cy + AC.h / 2;      // 586

  // flow state
  const hoa1   = v(values, '[default]Refrigeration/Compressor1/hoa');
  const hoa2   = v(values, '[default]Refrigeration/Compressor2/hoa');
  const anyOn  = (hoa1 != null && Number(hoa1) > 0) || (hoa2 != null && Number(hoa2) > 0);
  const FLOW   = anyOn ? 'pipe-flow' : '';

  // colours
  const DISC = '#ff8000';
  const COND = '#00d4ff';
  const LIQ  = '#4d9fff';

  // valve size (13 = half-width of bowtie)
  const VS = 13;

  return (
    <div style={{ background: '#0f172a', borderRadius: '12px', padding: '16px 16px 12px' }}>
      <h2 style={{ color: '#94a3b8', fontSize: '14px', fontWeight: 600, margin: '0 0 10px 0' }}>
        Refrigeration P&amp;ID
      </h2>

      <svg
        viewBox="0 0 1080 660"
        width="100%"
        style={{ maxHeight: '76vh', display: 'block' }}
        fontFamily="ui-sans-serif, system-ui, sans-serif"
      >
        <defs>
          <style>{`
            @keyframes pipeFlow  { to { stroke-dashoffset: -20; } }
            @keyframes fanSpin   { to { transform: rotate(360deg); } }
            @keyframes compPulse {
              0%,100% { transform: scale(1); }
              50%     { transform: scale(1.04); }
            }
            .pipe-flow {
              stroke-dasharray: 8 12;
              animation: pipeFlow 0.8s linear infinite;
            }
            .fan-spinning {
              animation: fanSpin 2s linear infinite;
            }
            .compressor-running {
              animation: compPulse 1.5s ease-in-out infinite;
              transform-box: fill-box;
              transform-origin: center;
            }
          `}</style>

          <pattern id="scada-grid" width="40" height="40" patternUnits="userSpaceOnUse">
            <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#1e293b" strokeWidth="0.5" />
          </pattern>

          <filter id="glow-sel" x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="5" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* ── SCADA background ── */}
        <rect width={1080} height={660} fill="#0f172a" />
        <rect width={1080} height={660} fill="url(#scada-grid)" />

        {/* ══════════════════════════════════════════════════════════════
            PIPING — drawn before equipment so symbols sit on top
        ══════════════════════════════════════════════════════════════ */}

        {/* ── DISCHARGE (orange) ── */}

        {/* header */}
        <line x1={LC_X} y1={HDR_Y} x2={RC_X} y2={HDR_Y}
          stroke={DISC} strokeWidth={6} strokeLinecap="round" className={FLOW} />
        <text x={(LC_X + RC_X) / 2} y={HDR_Y - 10} textAnchor="middle"
          fontSize={9} fontWeight="600" fill="#f97316">HOT GAS DISCHARGE</text>

        {/* left collect vertical */}
        <line x1={LC_X} y1={HDR_Y} x2={LC_X} y2={C2.cy}
          stroke={DISC} strokeWidth={5} strokeLinecap="round" className={FLOW} />

        {/* C1 discharge → collect */}
        <line x1={c1Right} y1={C1.cy} x2={LC_X} y2={C1.cy}
          stroke={DISC} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* C2 discharge → collect */}
        <line x1={c2Right} y1={C2.cy} x2={LC_X} y2={C2.cy}
          stroke={DISC} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* right collect vertical */}
        <line x1={RC_X} y1={HDR_Y} x2={RC_X} y2={RV.cy}
          stroke={DISC} strokeWidth={5} strokeLinecap="round" className={FLOW} />

        {/* T1 hot gas inlet tap */}
        <line x1={RC_X} y1={t1InY} x2={T1.x + T1.w} y2={t1InY}
          stroke={DISC} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* T2 hot gas inlet tap */}
        <line x1={RC_X} y1={t2InY} x2={T2.x + T2.w} y2={t2InY}
          stroke={DISC} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* ── CONDENSER (cyan) ── */}

        {/* T1 cond outlet → curved elbow → CON_X vertical */}
        <path
          d={`M ${T1.x},${t1OutY} L ${CON_X + 12},${t1OutY} Q ${CON_X},${t1OutY} ${CON_X},${t1OutY + 12}`}
          fill="none" stroke={COND} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* T2 cond outlet → CON_X horizontal */}
        <line x1={T2.x} y1={t2OutY} x2={CON_X} y2={t2OutY}
          stroke={COND} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* CON_X vertical: t1OutY+12 → RV.cy-12 */}
        <line x1={CON_X} y1={t1OutY + 12} x2={CON_X} y2={RV.cy - 12}
          stroke={COND} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* curved elbow at receiver → horizontal to RV left */}
        <path
          d={`M ${CON_X},${RV.cy - 12} Q ${CON_X},${RV.cy} ${CON_X + 12},${RV.cy} L ${rvLeftX},${RV.cy}`}
          fill="none" stroke={COND} strokeWidth={3} strokeLinecap="round" className={FLOW} />

        {/* CONDENSER label */}
        <text x={CON_X - 6} y={(t1OutY + RV.cy) / 2 + 4} textAnchor="end"
          fontSize={9} fontWeight="600" fill="#06b6d4">CONDENSER</text>

        {/* ── LIQUID / SUCTION (blue) ── */}

        {/* RV bottom → DV (vertical) */}
        <line x1={RV.cx} y1={rvBotY} x2={RV.cx} y2={DV.cy - VS - 2}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* DV → corner (vertical) */}
        <line x1={RV.cx} y1={DV.cy + VS + 2} x2={RV.cx} y2={KV.cy - 12}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* curved elbow at (RV.cx, KV.cy) turning left */}
        <path
          d={`M ${RV.cx},${KV.cy - 12} Q ${RV.cx},${KV.cy} ${RV.cx - 12},${KV.cy} L ${KV.cx + VS + 2},${KV.cy}`}
          fill="none" stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* KV → AC right */}
        <line x1={KV.cx - VS - 2} y1={KV.cy} x2={acRightX} y2={KV.cy}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* LIQUID label */}
        <text x={(KV.cx + acRightX) / 2} y={KV.cy - 8} textAnchor="middle"
          fontSize={9} fontWeight="600" fill="#60a5fa">LIQUID</text>

        {/* AC right → enter AC (short nub) */}
        <line x1={acRightX} y1={KV.cy} x2={acRightX} y2={AC.cy}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* AC bottom → suction header */}
        <line x1={AC.cx} y1={acBotY} x2={AC.cx} y2={SUC_Y}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* suction header */}
        <line x1={C1.cx} y1={SUC_Y} x2={AC.cx} y2={SUC_Y}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />
        <text x={(C1.cx + AC.cx) / 2} y={SUC_Y + 16} textAnchor="middle"
          fontSize={9} fontWeight="600" fill="#60a5fa">SUCTION RETURN</text>

        {/* C1 suction → header */}
        <line x1={C1.cx} y1={c1Bot} x2={C1.cx} y2={SUC_Y}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* C2 suction → header */}
        <line x1={C2.cx} y1={c2Bot} x2={C2.cx} y2={SUC_Y}
          stroke={LIQ} strokeWidth={4} strokeLinecap="round" className={FLOW} />

        {/* ── DATA CALLOUTS ── */}

        {/* Discharge pressure + temp (mid-header) */}
        <DataTag
          x={(LC_X + RC_X) / 2}
          y={HDR_Y + 12}
          lines={[
            `${fmt(v(values, '[default]Refrigeration/dischargePressure'), 1)} PSI`,
            `${fmt(v(values, '[default]Refrigeration/dischargeTemp'), 1)} °F`,
          ]}
        />

        {/* Suction pressure (suction header) */}
        <DataTag
          x={270}
          y={SUC_Y - 33}
          lines={[
            `S: ${fmt(v(values, '[default]Refrigeration/Compressor1/pressureSuction'), 1)} PSI`,
          ]}
        />

        {/* Condensing point between towers and receiver */}
        <DataTag
          x={CON_X - 38}
          y={t2OutY + 20}
          lines={[
            `Cond: ${fmt(v(values, '[default]Refrigeration/dischargeTemp'), 1)} °F`,
          ]}
        />

        {/* ══════════════════════════════════════════════════════════════
            EQUIPMENT — drawn on top of pipes
        ══════════════════════════════════════════════════════════════ */}

        <CompressorSymbol
          cx={C1.cx} cy={C1.cy} r={C1.r}
          num={1} values={values}
          selected={sel?.type === 'compressor' && sel.num === 1}
          onClick={() => toggle({ type: 'compressor', num: 1 })}
        />
        <CompressorSymbol
          cx={C2.cx} cy={C2.cy} r={C2.r}
          num={2} values={values}
          selected={sel?.type === 'compressor' && sel.num === 2}
          onClick={() => toggle({ type: 'compressor', num: 2 })}
        />

        <TowerSymbol
          x={T1.x} y={T1.y} w={T1.w} h={T1.h}
          num={1} values={values}
          selected={sel?.type === 'tower' && sel.num === 1}
          onClick={() => toggle({ type: 'tower', num: 1 })}
        />
        <TowerSymbol
          x={T2.x} y={T2.y} w={T2.w} h={T2.h}
          num={2} values={values}
          selected={sel?.type === 'tower' && sel.num === 2}
          onClick={() => toggle({ type: 'tower', num: 2 })}
        />

        <VesselSymbol
          cx={RV.cx} cy={RV.cy} w={RV.w} h={RV.h}
          label="Receiver"
          level={v(values, '[default]Refrigeration/receiverLevel')}
          fillColor="#06b6d4"
          selected={sel?.type === 'system'}
          onClick={() => toggle({ type: 'system' })}
        />

        <VesselSymbol
          cx={AC.cx} cy={AC.cy} w={AC.w} h={AC.h}
          label="Accumulator"
          level={v(values, '[default]Refrigeration/accumulatorLevel')}
          fillColor="#3b82f6"
          selected={sel?.type === 'system'}
          onClick={() => toggle({ type: 'system' })}
        />

        <ValveSymbol
          cx={DV.cx} cy={DV.cy} size={VS}
          label="Disch Valve"
          position={v(values, '[default]Refrigeration/valveDischarge')}
          selected={sel?.type === 'system'}
          onClick={() => toggle({ type: 'system' })}
        />

        <ValveSymbol
          cx={KV.cx} cy={KV.cy} size={VS}
          label="King Valve"
          position={v(values, '[default]Refrigeration/valveKing')}
          selected={sel?.type === 'system'}
          onClick={() => toggle({ type: 'system' })}
        />
      </svg>
    </div>
  );
}
