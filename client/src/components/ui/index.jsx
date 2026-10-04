import { AlertTriangle, ChevronLeft, ChevronRight, Loader2, Star, X } from 'lucide-react';
import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';

/* ------------------------------------------------------------------ */
/* Feedback                                                            */
/* ------------------------------------------------------------------ */

export function Spinner({ className = 'h-5 w-5' }) {
  return <Loader2 className={`animate-spin text-rose-500 ${className}`} aria-hidden />;
}

export function PageLoader({ label = 'Loading…' }) {
  return (
    <div className="flex min-h-[40vh] flex-col items-center justify-center gap-3 text-ink-500" role="status">
      <span className="animate-float text-4xl">🌸</span>
      <span className="text-sm">{label}</span>
    </div>
  );
}

export function Skeleton({ className = '' }) {
  return <div className={`animate-pulse rounded-2xl bg-rose-100/60 ${className}`} />;
}

export function EmptyState({ emoji = '🌷', title, text, action }) {
  return (
    <div className="card-pad flex flex-col items-center gap-3 py-14 text-center">
      <span className="text-5xl">{emoji}</span>
      <h3 className="text-xl font-semibold">{title}</h3>
      {text && <p className="max-w-md text-sm text-ink-500">{text}</p>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className="card-pad flex flex-col items-center gap-3 py-10 text-center">
      <AlertTriangle className="h-8 w-8 text-crimson-500" />
      <p className="text-sm text-ink-600">{message}</p>
      {onRetry && (
        <button type="button" className="btn-secondary btn-sm" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Badges                                                              */
/* ------------------------------------------------------------------ */

const TONES = {
  amber: 'bg-amber-50 text-amber-700 ring-amber-200',
  blue: 'bg-sky-50 text-sky-700 ring-sky-200',
  lilac: 'bg-lilac-100 text-lilac-600 ring-lilac-200',
  teal: 'bg-teal-50 text-teal-700 ring-teal-200',
  rose: 'bg-rose-50 text-rose-700 ring-rose-200',
  green: 'bg-leaf-50 text-leaf-700 ring-leaf-200',
  red: 'bg-red-50 text-red-700 ring-red-200',
  gray: 'bg-gray-100 text-gray-600 ring-gray-200',
};

export function Badge({ tone = 'gray', children, className = '' }) {
  return <span className={`chip ring-1 ring-inset ${TONES[tone] || TONES.gray} ${className}`}>{children}</span>;
}

/** Renders a status from one of the *_STATUS maps in utils/format. */
export function StatusBadge({ map, status }) {
  const s = map[status] || { label: status, tone: 'gray' };
  return <Badge tone={s.tone}>{s.label}</Badge>;
}

export function Stars({ value = 0, count, size = 'h-4 w-4' }) {
  return (
    <span className="inline-flex items-center gap-1" aria-label={`Rated ${Number(value).toFixed(1)} out of 5`}>
      <span className="flex">
        {[1, 2, 3, 4, 5].map((i) => (
          <Star key={i} className={`${size} ${i <= Math.round(value) ? 'fill-amber-400 text-amber-400' : 'text-rose-100'}`} />
        ))}
      </span>
      {count !== undefined && <span className="text-xs text-ink-400">({count})</span>}
    </span>
  );
}

export function StarInput({ value, onChange, size = 'h-7 w-7' }) {
  return (
    <div className="flex gap-1" role="radiogroup" aria-label="Rating">
      {[1, 2, 3, 4, 5].map((i) => (
        <button
          key={i}
          type="button"
          role="radio"
          aria-checked={value === i}
          aria-label={`${i} star${i > 1 ? 's' : ''}`}
          onClick={() => onChange(i)}
          className="transition hover:scale-110"
        >
          <Star className={`${size} ${i <= value ? 'fill-amber-400 text-amber-400' : 'text-rose-200'}`} />
        </button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Form fields                                                         */
/* ------------------------------------------------------------------ */

export function Field({ label, error, hint, children, className = '', required }) {
  return (
    <div className={className}>
      {label && (
        <label className="label">
          {label} {required && <span className="text-rose-500">*</span>}
        </label>
      )}
      {children}
      {error ? <p className="field-error">{error}</p> : hint ? <p className="mt-1 text-xs text-ink-400">{hint}</p> : null}
    </div>
  );
}

/** Text input wired to a form object: <Input form={form} name="email" ... /> */
export function Input({ label, error, hint, className = '', required, ...props }) {
  const id = useId();
  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="label">
          {label} {required && <span className="text-rose-500">*</span>}
        </label>
      )}
      <input id={id} className={`input ${error ? 'input-error' : ''}`} aria-invalid={Boolean(error)} {...props} />
      {error ? <p className="field-error">{error}</p> : hint ? <p className="mt-1 text-xs text-ink-400">{hint}</p> : null}
    </div>
  );
}

export function Select({ label, error, className = '', children, required, ...props }) {
  const id = useId();
  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="label">
          {label} {required && <span className="text-rose-500">*</span>}
        </label>
      )}
      <select id={id} className={`input ${error ? 'input-error' : ''}`} {...props}>
        {children}
      </select>
      {error && <p className="field-error">{error}</p>}
    </div>
  );
}

export function Textarea({ label, error, className = '', hint, ...props }) {
  const id = useId();
  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="label">
          {label}
        </label>
      )}
      <textarea id={id} className={`input min-h-[96px] resize-y ${error ? 'input-error' : ''}`} {...props} />
      {error ? <p className="field-error">{error}</p> : hint ? <p className="mt-1 text-xs text-ink-400">{hint}</p> : null}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Modal & confirm                                                     */
/* ------------------------------------------------------------------ */

export function Modal({ open, onClose, title, children, footer, size = 'max-w-lg' }) {
  const panel = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => e.key === 'Escape' && onClose?.();
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    panel.current?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  if (!open) return null;
  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-end justify-center p-0 sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-label={title}>
      <div className="absolute inset-0 bg-ink-900/40 backdrop-blur-sm" onClick={onClose} />
      <div
        ref={panel}
        tabIndex={-1}
        className={`relative max-h-[92vh] w-full ${size} animate-fade-up overflow-y-auto rounded-t-3xl bg-white shadow-card sm:rounded-3xl`}
      >
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-rose-100 bg-white/95 px-6 py-4 backdrop-blur">
          <h2 className="text-xl font-semibold">{title}</h2>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="Close">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="px-6 py-5">{children}</div>
        {footer && <div className="flex flex-wrap justify-end gap-2 border-t border-rose-100 px-6 py-4">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({ open, title, message, confirmLabel = 'Confirm', danger, busy, onConfirm, onClose }) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size="max-w-md"
      footer={
        <>
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className={danger ? 'btn-danger' : 'btn-primary'} onClick={onConfirm} disabled={busy}>
            {busy && <Spinner className="h-4 w-4 text-white" />} {confirmLabel}
          </button>
        </>
      }
    >
      <p className="text-sm text-ink-600">{message}</p>
    </Modal>
  );
}

/* ------------------------------------------------------------------ */
/* Layout helpers                                                      */
/* ------------------------------------------------------------------ */

export function PageHeader({ title, subtitle, actions }) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h1 className="text-2xl font-semibold sm:text-3xl">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

const STAT_TONES = {
  rose: 'from-rose-100 to-rose-50 text-rose-600',
  green: 'from-leaf-100 to-leaf-50 text-leaf-600',
  lilac: 'from-lilac-200 to-lilac-50 text-lilac-600',
  amber: 'from-amber-100 to-amber-50 text-amber-600',
  sky: 'from-sky-100 to-sky-50 text-sky-600',
  red: 'from-red-100 to-red-50 text-crimson-600',
};

export function StatCard({ icon: Icon, label, value, tone = 'rose', hint, onClick }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={`card-pad group flex items-center gap-4 text-left transition ${onClick ? 'hover:-translate-y-0.5 hover:shadow-card' : ''}`}
    >
      <span className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br ${STAT_TONES[tone]}`}>
        <Icon className="h-6 w-6" />
      </span>
      <span className="min-w-0">
        <span className="block truncate text-xs font-medium uppercase tracking-wide text-ink-400">{label}</span>
        <span className="block truncate font-display text-2xl font-semibold text-ink-900">{value}</span>
        {hint && <span className="block truncate text-xs text-ink-400">{hint}</span>}
      </span>
    </Tag>
  );
}

export function Pagination({ page, pages, onChange }) {
  if (!pages || pages <= 1) return null;
  return (
    <nav className="mt-6 flex items-center justify-center gap-2" aria-label="Pagination">
      <button type="button" className="btn-icon" disabled={page <= 1} onClick={() => onChange(page - 1)} aria-label="Previous page">
        <ChevronLeft className="h-5 w-5" />
      </button>
      <span className="text-sm text-ink-500">
        Page <strong className="text-ink-900">{page}</strong> of {pages}
      </span>
      <button type="button" className="btn-icon" disabled={page >= pages} onClick={() => onChange(page + 1)} aria-label="Next page">
        <ChevronRight className="h-5 w-5" />
      </button>
    </nav>
  );
}

export function Tabs({ tabs, value, onChange }) {
  return (
    <div className="scrollbar-none -mx-1 mb-5 flex gap-2 overflow-x-auto px-1 pb-1">
      {tabs.map(([key, label, count]) => (
        <button
          key={key}
          type="button"
          onClick={() => onChange(key)}
          className={`chip shrink-0 px-4 py-2 text-sm transition ${
            value === key ? 'bg-rose-500 text-white shadow-soft' : 'bg-white text-ink-600 ring-1 ring-rose-100 hover:bg-rose-50'
          }`}
        >
          {label}
          {count !== undefined && (
            <span className={`rounded-full px-1.5 text-[11px] ${value === key ? 'bg-white/25' : 'bg-rose-100 text-rose-700'}`}>{count}</span>
          )}
        </button>
      ))}
    </div>
  );
}

export function Avatar({ user, size = 'h-10 w-10', className = '' }) {
  const text = `${user?.firstName?.[0] || ''}${user?.lastName?.[0] || ''}`.toUpperCase() || '🌸';
  return user?.avatarUrl ? (
    <img src={user.avatarUrl} alt="" className={`${size} shrink-0 rounded-full object-cover ring-2 ring-white ${className}`} />
  ) : (
    <span
      className={`${size} inline-flex shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-rose-400 to-lilac-400 text-sm font-semibold text-white ring-2 ring-white ${className}`}
    >
      {text}
    </span>
  );
}
