import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { api, errorMessage } from '../api/client';
import { useAuth } from './AuthContext';

/**
 * Cart + wishlist state.
 *  - Guests: the cart is kept in localStorage and priced by POST /cart/quote.
 *  - Customers: the cart lives in PostgreSQL; a guest cart is merged on sign-in.
 *  - Staff / admins don't shop, so the cart is hidden for them.
 */
const ShopContext = createContext(null);
const GUEST_KEY = 'bloom_guest_cart';
const EMPTY = { items: [], saved: [], summary: { subtotal: 0, discount: 0, deliveryFee: 0, total: 0, itemCount: 0, savings: 0 } };

const readGuest = () => {
  try {
    return JSON.parse(localStorage.getItem(GUEST_KEY)) || [];
  } catch {
    return [];
  }
};
const writeGuest = (items) => {
  try {
    localStorage.setItem(GUEST_KEY, JSON.stringify(items));
  } catch {
    /* storage unavailable - cart just won't persist */
  }
};

export function ShopProvider({ children }) {
  const { user, loading: authLoading } = useAuth();
  const isCustomer = user?.role === 'customer';
  const isGuest = !user;
  const [cart, setCart] = useState(EMPTY);
  const [cartLoading, setCartLoading] = useState(true);
  const [wishlist, setWishlist] = useState([]);
  const [bump, setBump] = useState(0); // animates the cart badge
  const lastUser = useRef(undefined);

  const quoteGuest = useCallback(async (items) => {
    if (!items.length) return setCart(EMPTY);
    const { data } = await api.post('/cart/quote', { items });
    setCart(data);
  }, []);

  // Load the right cart whenever the signed-in user changes.
  useEffect(() => {
    if (authLoading || lastUser.current === (user?.id ?? null)) return;
    lastUser.current = user?.id ?? null;
    setCartLoading(true);
    (async () => {
      try {
        if (isCustomer) {
          const guest = readGuest();
          const { data } = guest.length ? await api.post('/cart/merge', { items: guest }) : await api.get('/cart');
          if (guest.length) writeGuest([]);
          setCart(data);
          setWishlist((await api.get('/wishlist')).data);
        } else if (isGuest) {
          setWishlist([]);
          await quoteGuest(readGuest());
        } else {
          setCart(EMPTY);
          setWishlist([]);
        }
      } catch {
        setCart(EMPTY);
      } finally {
        setCartLoading(false);
      }
    })();
  }, [authLoading, user, isCustomer, isGuest, quoteGuest]);

  const run = useCallback(async (fn) => {
    try {
      await fn();
      return true;
    } catch (err) {
      toast.error(errorMessage(err));
      return false;
    }
  }, []);

  const addToCart = useCallback(
    async (product, quantity = 1) => {
      if (user && !isCustomer) {
        toast('Shopping is available on customer accounts 🌸');
        return false;
      }
      const ok = await run(async () => {
        if (isCustomer) {
          setCart((await api.post('/cart/items', { productId: product.id, quantity })).data);
        } else {
          const items = readGuest();
          const line = items.find((i) => i.productId === product.id);
          const next = Math.min(99, (line?.quantity || 0) + quantity);
          if (next > product.stock) throw new Error(`Only ${product.stock} ${product.name} left in stock`);
          const updated = line
            ? items.map((i) => (i.productId === product.id ? { ...i, quantity: next } : i))
            : [...items, { productId: product.id, quantity }];
          writeGuest(updated);
          await quoteGuest(updated);
        }
      }).catch(() => false);
      if (ok) {
        setBump((b) => b + 1);
        toast.success(`🌸 ${product.name} added to your cart!`);
      }
      return ok;
    },
    [user, isCustomer, run, quoteGuest],
  );

  const setQuantity = useCallback(
    (productId, quantity) =>
      run(async () => {
        if (quantity < 1) return;
        if (isCustomer) {
          setCart((await api.patch(`/cart/items/${productId}`, { quantity })).data);
        } else {
          const updated = readGuest().map((i) => (i.productId === productId ? { ...i, quantity } : i));
          writeGuest(updated);
          await quoteGuest(updated);
        }
      }),
    [isCustomer, run, quoteGuest],
  );

  const removeFromCart = useCallback(
    (productId) =>
      run(async () => {
        if (isCustomer) {
          setCart((await api.delete(`/cart/items/${productId}`)).data);
        } else {
          const updated = readGuest().filter((i) => i.productId !== productId);
          writeGuest(updated);
          await quoteGuest(updated);
        }
      }),
    [isCustomer, run, quoteGuest],
  );

  const saveForLater = useCallback(
    (productId, saved = true) =>
      run(async () => {
        setCart((await api.patch(`/cart/items/${productId}`, { savedForLater: saved })).data);
        toast.success(saved ? 'Saved for later 💾' : 'Moved back to your cart');
      }),
    [run],
  );

  const reloadCart = useCallback(async () => {
    if (isCustomer) setCart((await api.get('/cart')).data);
    else if (isGuest) await quoteGuest(readGuest());
  }, [isCustomer, isGuest, quoteGuest]);

  const wishlistIds = useMemo(() => new Set(wishlist.map((p) => p.id)), [wishlist]);

  const toggleWishlist = useCallback(
    async (product) => {
      if (!user) {
        toast('Sign in to save your favourite flowers ❤️');
        return false;
      }
      if (!isCustomer) return false;
      const inList = wishlistIds.has(product.id);
      return run(async () => {
        const { data } = inList ? await api.delete(`/wishlist/${product.id}`) : await api.post(`/wishlist/${product.id}`);
        setWishlist(data);
        toast.success(inList ? `${product.name} removed from your wishlist` : `❤️ ${product.name} added to your wishlist`);
      });
    },
    [user, isCustomer, wishlistIds, run],
  );

  const value = useMemo(
    () => ({
      cart,
      cartLoading,
      cartCount: cart.summary?.itemCount || 0,
      bump,
      canShop: !user || isCustomer,
      addToCart,
      setQuantity,
      removeFromCart,
      saveForLater,
      reloadCart,
      wishlist,
      wishlistIds,
      toggleWishlist,
    }),
    [cart, cartLoading, bump, user, isCustomer, addToCart, setQuantity, removeFromCart, saveForLater, reloadCart, wishlist, wishlistIds, toggleWishlist],
  );

  return <ShopContext.Provider value={value}>{children}</ShopContext.Provider>;
}

export const useShop = () => useContext(ShopContext);
