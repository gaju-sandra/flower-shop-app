import { ShoppingBag } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useShop } from '../../context/ShopContext';

export default function CartButton({ to = '/cart', className = '' }) {
  const { cartCount, bump, canShop } = useShop();
  if (!canShop) return null;
  return (
    <Link to={to} className={`btn-icon relative ${className}`} aria-label={`Cart, ${cartCount} items`}>
      <ShoppingBag className="h-5 w-5" />
      {cartCount > 0 && (
        <span
          key={bump}
          className="absolute -right-0.5 -top-0.5 flex h-5 min-w-[20px] animate-pop items-center justify-center rounded-full bg-rose-500 px-1 text-[11px] font-semibold text-white ring-2 ring-white"
        >
          {cartCount > 99 ? '99+' : cartCount}
        </span>
      )}
    </Link>
  );
}
