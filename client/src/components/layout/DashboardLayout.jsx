import {
  BarChart3, Bike, Boxes, CreditCard, Flower2, Gift, Heart, Home, Info, LayoutDashboard, LogOut, MapPin, Menu,
  MessageSquare, Package, Phone, Settings, ShoppingBag, Star, User, UserCog, Users, X,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useShop } from '../../context/ShopContext';
import { STAFF_ROLES } from '../../utils/format';
import { Avatar, ConfirmDialog } from '../ui';
import CartButton from './CartButton';
import Logo from './Logo';

/** Sidebar entries per role. RBAC is enforced by the API; this only shapes navigation. */
const MENUS = {
  customer: [
    ['/account', 'Dashboard', Home, true],
    ['/account/products', 'Products', Flower2],
    ['/account/cart', 'My Cart', ShoppingBag],
    ['/account/orders', 'My Orders', Package],
    ['/account/wishlist', 'Wishlist', Heart],
    ['/account/addresses', 'Delivery Address', MapPin],
    ['/account/profile', 'My Profile', User],
    ['/account/about', 'About Us', Info],
    ['/account/contact', 'Contact Us', Phone],
  ],
  staff: [
    ['/staff', 'Dashboard', LayoutDashboard, true],
    ['/staff/orders', 'Orders', Package],
    ['/staff/customers', 'Customers', Users],
    ['/staff/products', 'Products', Boxes],
    ['/staff/deliveries', 'Deliveries', Bike],
    ['/staff/messages', 'Messages', MessageSquare],
    ['/staff/profile', 'Profile', User],
  ],
  admin: [
    ['/admin', 'Dashboard', Home, true],
    ['/admin/products', 'Products', Flower2],
    ['/admin/orders', 'Orders', Package],
    ['/admin/customers', 'Customers', Users],
    ['/admin/staff', 'Staff', UserCog],
    ['/admin/deliveries', 'Deliveries', Bike],
    ['/admin/payments', 'Payments', CreditCard],
    ['/admin/promotions', 'Promotions', Gift],
    ['/admin/reports', 'Reports', BarChart3],
    ['/admin/reviews', 'Reviews', Star],
    ['/admin/messages', 'Messages', MessageSquare],
    ['/admin/settings', 'Settings', Settings],
  ],
};

const ROLE_LABEL = { customer: 'Customer', staff: 'Staff', admin: 'Administrator' };

function Sidebar({ role, onNavigate, onLogout }) {
  const { user } = useAuth();
  const { cartCount, wishlist } = useShop();
  const badges = { '/account/cart': cartCount, '/account/wishlist': wishlist.length };

  return (
    <div className="flex h-full flex-col">
      <div className="px-5 pb-4 pt-5">
        <Logo to="/" />
      </div>
      <div className="mx-4 mb-4 flex items-center gap-3 rounded-2xl bg-gradient-to-r from-rose-50 to-lilac-50 p-3">
        <Avatar user={user} />
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-ink-900">
            {user?.firstName} {user?.lastName}
          </p>
          <p className="truncate text-xs text-ink-500">{user?.staffRole ? STAFF_ROLES[user.staffRole] : ROLE_LABEL[role]}</p>
        </div>
      </div>
      <nav className="flex-1 space-y-1 overflow-y-auto px-3 pb-4" aria-label="Dashboard">
        {MENUS[role].map(([to, label, Icon, end]) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            onClick={onNavigate}
            className={({ isActive }) =>
              `group flex items-center gap-3 rounded-2xl px-4 py-2.5 text-sm font-medium transition ${
                isActive
                  ? 'bg-gradient-to-r from-rose-500 to-rose-400 text-white shadow-soft'
                  : 'text-ink-600 hover:bg-rose-50 hover:text-rose-700'
              }`
            }
          >
            <Icon className="h-[18px] w-[18px] shrink-0" />
            <span className="flex-1">{label}</span>
            {badges[to] > 0 && (
              <span className="rounded-full bg-rose-100 px-2 py-0.5 text-[11px] font-semibold text-rose-700 group-[.text-white]:bg-white/25">
                {badges[to]}
              </span>
            )}
          </NavLink>
        ))}
      </nav>
      <div className="border-t border-rose-100 p-3">
        <button
          type="button"
          onClick={onLogout}
          className="flex w-full items-center gap-3 rounded-2xl px-4 py-2.5 text-sm font-medium text-ink-600 transition hover:bg-red-50 hover:text-crimson-600"
        >
          <LogOut className="h-[18px] w-[18px]" /> Logout
        </button>
      </div>
    </div>
  );
}

export default function DashboardLayout({ role }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [confirmLogout, setConfirmLogout] = useState(false);

  useEffect(() => {
    setMobileOpen(false);
    document.getElementById('dashboard-main')?.scrollTo?.(0, 0);
    window.scrollTo(0, 0);
  }, [pathname]);

  const doLogout = async () => {
    await logout();
    navigate('/', { replace: true });
  };

  const current = MENUS[role].slice().reverse().find(([to]) => pathname === to || pathname.startsWith(`${to}/`));

  return (
    <div className="min-h-screen bg-gradient-to-br from-cream-50 via-white to-rose-50/40">
      {/* fixed sidebar on desktop */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-72 border-r border-rose-100 bg-white/90 backdrop-blur lg:block">
        <Sidebar role={role} onLogout={() => setConfirmLogout(true)} />
      </aside>

      {/* drawer on mobile / tablet */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-ink-900/40 backdrop-blur-sm" onClick={() => setMobileOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-[82%] max-w-xs animate-fade-up bg-white shadow-card">
            <button type="button" className="btn-icon absolute right-3 top-5" onClick={() => setMobileOpen(false)} aria-label="Close menu">
              <X className="h-5 w-5" />
            </button>
            <Sidebar role={role} onNavigate={() => setMobileOpen(false)} onLogout={() => setConfirmLogout(true)} />
          </aside>
        </div>
      )}

      <div className="lg:pl-72">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-rose-100 bg-white/80 px-4 backdrop-blur-lg sm:px-6 lg:px-8">
          <button type="button" className="btn-icon lg:hidden" onClick={() => setMobileOpen(true)} aria-label="Open menu">
            <Menu className="h-5 w-5" />
          </button>
          <p className="flex-1 truncate font-display text-lg font-semibold text-ink-900">{current?.[1] || 'Dashboard'}</p>
          {role === 'customer' && <CartButton to="/account/cart" />}
          <NavLink to={`/${role === 'customer' ? 'account' : role}/profile`} className="rounded-full" aria-label="My profile">
            <Avatar user={user} size="h-9 w-9" />
          </NavLink>
        </header>
        <main id="dashboard-main" className="px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <div className="mx-auto max-w-7xl animate-fade-up">
            <Outlet />
          </div>
        </main>
      </div>

      <ConfirmDialog
        open={confirmLogout}
        title="Log out?"
        message="You will need to sign in again to see your dashboard."
        confirmLabel="Log out"
        onConfirm={doLogout}
        onClose={() => setConfirmLogout(false)}
      />
    </div>
  );
}
