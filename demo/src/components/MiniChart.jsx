import { useEffect, useRef } from 'react';
import Chart from 'chart.js/auto';

/**
 * Small inline sparkline chart (no axes).
 *
 * @param {{
 *   data: Array<{ x: number, y: number }>,
 *   color?: string,
 *   height?: number,
 * }} props
 */
export default function MiniChart({ data = [], color = '#2563eb', height = 100 }) {
  const canvasRef = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    if (!canvasRef.current) return;

    if (chartRef.current) {
      chartRef.current.destroy();
      chartRef.current = null;
    }

    const ctx = canvasRef.current.getContext('2d');

    // Gradient fill
    const gradient = ctx.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, color + '33');
    gradient.addColorStop(1, color + '00');

    chartRef.current = new Chart(ctx, {
      type: 'line',
      data: {
        datasets: [
          {
            data,
            borderColor: color,
            backgroundColor: gradient,
            fill: true,
            tension: 0.3,
            pointRadius: 0,
            borderWidth: 1.5,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: {
          legend: { display: false },
          tooltip: { enabled: false },
        },
        scales: {
          x: { display: false, type: 'linear' },
          y: { display: false },
        },
      },
    });

    return () => {
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, [data, color, height]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div style={{ height }}>
      <canvas ref={canvasRef} />
    </div>
  );
}
