/**
 * Tiny in-process TTL cache for hot, read-mostly queries (catalogue, settings).
 * Entries are grouped by a namespace prefix so writes can invalidate a group.
 */
const store = new Map();

export async function cached(key, ttlMs, loader) {
  const hit = store.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  const value = await loader();
  store.set(key, { value, expires: Date.now() + ttlMs });
  if (store.size > 1000) store.delete(store.keys().next().value);
  return value;
}

export function invalidate(prefix) {
  for (const key of store.keys()) if (key.startsWith(prefix)) store.delete(key);
}
