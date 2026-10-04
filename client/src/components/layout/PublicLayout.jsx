import { Facebook, Instagram, LayoutDashboard, Mail, MapPin, Menu, MessageCircle, Phone, Twitter, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useSettings } from '../../context/SettingsContext';
import { homeFor } from '../../utils/format';
import { Avatar } from '../ui';
import CartButton from './CartButton';
import Logo from './Logo';

const LINKS = [
  ['/', 'Home'],
  ['/products', 'Products'],
  ['/about', 'About Us'],
  ['/contact', 'Contact'],
];

function Navbar() {
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { pathname } = useLocation();

  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 12);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const linkClass = ({ isActive }) =>
    `rounded-full px-4 py-2 text-sm font-medium transition ${isActive ? 'bg-rose-50 text-rose-700' : 'text-ink-600 hover:text-rose-600'}`;

  return (
    <header
      className={`sticky top-0 z-40 transition-all ${scrolled ? 'border-b border-rose-100 bg-white/90 shadow-soft backdrop-blur-lg' : 'bg-cream-50/70 backdrop-blur'}`}
    >
      <nav className="container-page flex h-16 items-center justify-between gap-4 sm:h-20">
        <Logo />
        <div className="hidden items-center gap-1 md:flex">
          {LINKS.map(([to, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={linkClass}>
              {label}
            </NavLink>
          ))}
        </div>
        <div className="flex items-center gap-1 sm:gap-2">
          <CartButton />
          {user ? (
            <Link to={homeFor(user.role)} className="btn-secondary hidden !py-1.5 !pl-1.5 sm:inline-flex">
              <Avatar user={user} size="h-7 w-7" />
              <span>My dashboard</span>
            </Link>
          ) : (
            <div className="hidden items-center gap-2 sm:flex">
              <Link to="/login" className="btn-ghost">
                Login
              </Link>
              <Link to="/register" className="btn-primary">
                Sign Up
              </Link>
            </div>
          )}
          <button type="button" className="btn-icon md:hidden" onClick={() => setOpen((o) => !o)} aria-label="Menu" aria-expanded={open}>
            {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>
      </nav>
      {open && (
        <div className="animate-fade-up border-t border-rose-100 bg-white md:hidden">
          <div className="container-page flex flex-col gap-1 py-4">
            {LINKS.map(([to, label]) => (
              <NavLink key={to} to={to} end={to === '/'} className={linkClass}>
                {label}
              </NavLink>
            ))}
            <div className="mt-3 grid grid-cols-2 gap-2">
              {user ? (
                <Link to={homeFor(user.role)} className="btn-primary col-span-2">
                  <LayoutDashboard className="h-4 w-4" /> My dashboard
                </Link>
              ) : (
                <>
                  <Link to="/login" className="btn-secondary">
                    Login
                  </Link>
                  <Link to="/register" className="btn-primary">
                    Sign Up
                  </Link>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </header>
  );
}

export function Footer() {
  const { store, social } = useSettings();
  const socials = [
    [social.instagram, Instagram, 'Instagram'],
    [social.facebook, Facebook, 'Facebook'],
    [social.x, Twitter, 'X'],
    [social.whatsapp, MessageCircle, 'WhatsApp'],
  ].filter(([href]) => href);
  return (
    <footer className="mt-20 bg-gradient-to-br from-ink-900 via-[#3a1f2c] to-rose-900 text-rose-50">
      <div className="container-page grid gap-10 py-14 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-4">
          <Logo light />
          <p className="text-sm text-rose-100/80">Fresh flowers, hand-arranged in Kigali and delivered with love to make every moment special.</p>
          <div className="flex gap-2">
            {socials.map(([href, Icon, label]) => (
              <a
                key={label}
                href={href}
                target="_blank"
                rel="noreferrer"
                aria-label={label}
                className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 transition hover:bg-rose-500"
              >
                <Icon className="h-4 w-4" />
              </a>
            ))}
          </div>
        </div>
        <div>
          <h3 className="mb-4 font-display text-lg text-white">Shop</h3>
          <ul className="space-y-2 text-sm text-rose-100/80">
            <li><Link className="hover:text-white" to="/products?category=roses">Roses</Link></li>
            <li><Link className="hover:text-white" to="/products?category=bouquets">Bouquets</Link></li>
            <li><Link className="hover:text-white" to="/products?category=wedding-flowers">Wedding Flowers</Link></li>
            <li><Link className="hover:text-white" to="/products?sort=popular">Best sellers</Link></li>
          </ul>
        </div>
        <div>
          <h3 className="mb-4 font-display text-lg text-white">Company</h3>
          <ul className="space-y-2 text-sm text-rose-100/80">
            <li><Link className="hover:text-white" to="/about">About Us</Link></li>
            <li><Link className="hover:text-white" to="/contact">Contact</Link></li>
            <li><Link className="hover:text-white" to="/login">Track an order</Link></li>
            <li><Link className="hover:text-white" to="/register">Create an account</Link></li>
          </ul>
        </div>
        <div>
          <h3 className="mb-4 font-display text-lg text-white">Visit us</h3>
          <ul className="space-y-3 text-sm text-rose-100/80">
            <li className="flex gap-2"><MapPin className="mt-0.5 h-4 w-4 shrink-0" />{store.address}</li>
            <li className="flex gap-2"><Phone className="mt-0.5 h-4 w-4 shrink-0" />{store.phone}</li>
            <li className="flex gap-2"><Mail className="mt-0.5 h-4 w-4 shrink-0" />{store.email}</li>
          </ul>
        </div>
      </div>
      <div className="border-t border-white/10 py-5 text-center text-xs text-rose-100/60">
        © {new Date().getFullYear()} {store.name} · Made with 🌸 in Kigali
      </div>
    </footer>
  );
}

export default function PublicLayout() {
  const { pathname } = useLocation();
  useEffect(() => window.scrollTo(0, 0), [pathname]);
  return (
    <div className="flex min-h-screen flex-col">
      <Navbar />
      <main className="flex-1">
        <Outlet />
      </main>
      <Footer />
    </div>
  );
}
