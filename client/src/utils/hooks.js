import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { api, errorMessage } from '../api/client';

/**
 * GET a resource and track loading / error state.
 *   const { data, loading, error, reload, setData } = useFetch('/orders/mine', { params });
 * Pass `null` as the url to skip fetching.
 */
export function useFetch(url, options = {}, deps = []) {
  const [state, setState] = useState({ data: options.initial ?? null, loading: Boolean(url), error: null });
  const counter = useRef(0);
  const key = JSON.stringify(options.params || {});

  const load = useCallback(async () => {
    if (!url) return;
    const id = ++counter.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    try {
      const { data } = await api.get(url, { params: options.params });
      if (id === counter.current) setState({ data, loading: false, error: null });
    } catch (err) {
      if (id === counter.current) setState((s) => ({ ...s, loading: false, error: errorMessage(err) }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, key, ...deps]);

  useEffect(() => {
    load();
  }, [load]);

  const setData = useCallback((updater) => setState((s) => ({ ...s, data: typeof updater === 'function' ? updater(s.data) : updater })), []);
  return { ...state, reload: load, setData };
}

export function useDebounce(value, delay = 350) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return v;
}

/** "/account" when rendered inside the customer dashboard, "" on the public site. */
export function useShopBase() {
  const { pathname } = useLocation();
  return pathname.startsWith('/account') ? '/account' : '';
}

export function useDocumentTitle(title) {
  useEffect(() => {
    document.title = title ? `${title} · Bloom & Co.` : 'Bloom & Co. · Flowers Delivered With Love';
  }, [title]);
}
