import { Link } from 'react-router-dom';

export default function Logo({ to = '/', light = false, compact = false }) {
  return (
    <Link to={to} className="group inline-flex items-center gap-2" aria-label="Bloom & Co. home">
      <span className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-to-br from-rose-400 via-rose-500 to-lilac-400 text-xl shadow-soft transition group-hover:rotate-12">
        🌸
      </span>
      {!compact && (
        <span className="leading-none">
          <span className={`block font-display text-xl font-semibold ${light ? 'text-white' : 'text-ink-900'}`}>
            Bloom <span className="text-rose-500">&amp;</span> Co.
          </span>
          <span className={`block text-[10px] uppercase tracking-[0.25em] ${light ? 'text-rose-100' : 'text-ink-400'}`}>
            Kigali florist
          </span>
        </span>
      )}
    </Link>
  );
}
