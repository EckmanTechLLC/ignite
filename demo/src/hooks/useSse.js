import { useState, useEffect, useRef } from 'react';
import { openSse } from '../api';

/**
 * Reusable SSE hook. Manages EventSource lifecycle for a set of tag paths.
 *
 * @param {string[]} paths - Tag paths to subscribe to. Pass [] to disconnect.
 * @returns {{ values: Object, status: string, clear: Function }}
 *   values: { [path]: { value, quality, timestamp } }
 *   status: 'disconnected' | 'connecting' | 'connected' | 'error'
 *   clear: Clears the accumulated values map
 */
export function useSse(paths = []) {
  const [values, setValues] = useState({});
  const [status, setStatus] = useState('disconnected');
  const esRef = useRef(null);

  // Stable key so effect only re-runs when the path list actually changes
  const pathKey = Array.isArray(paths) ? paths.filter(Boolean).join(',') : '';

  useEffect(() => {
    if (esRef.current) {
      esRef.current.close();
      esRef.current = null;
    }

    if (!pathKey) {
      setStatus('disconnected');
      return;
    }

    const pathList = pathKey.split(',');
    const es = openSse(
      pathList,
      (event) => {
        if (event.type === 'tagChange') {
          setValues((prev) => ({
            ...prev,
            [event.path]: {
              value: event.value,
              quality: event.quality,
              timestamp: event.timestamp,
            },
          }));
        }
      },
      setStatus
    );

    esRef.current = es;
    return () => {
      es.close();
      esRef.current = null;
    };
  }, [pathKey]); // eslint-disable-line react-hooks/exhaustive-deps

  function clear() {
    setValues({});
  }

  return { values, status, clear };
}
