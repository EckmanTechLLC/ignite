import { useSse } from './useSse';

/**
 * Manages a live SSE subscription for a set of tag paths.
 * Thin wrapper around useSse — use this for page-level subscriptions.
 *
 * @param {string[]} paths - Tag paths to subscribe to
 * @returns {{ values: Object, status: string, clear: Function }}
 *   values: { [path]: { value, quality, timestamp } }
 */
export function useLiveValues(paths = []) {
  return useSse(paths);
}
