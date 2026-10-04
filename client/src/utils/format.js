export const rwf = (n) => `RWF ${Math.round(Number(n) || 0).toLocaleString('en-US')}`;

export const shortRwf = (n) => {
  const v = Number(n) || 0;
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  if (v >= 1_000) return `${Math.round(v / 1_000)}k`;
  return String(v);
};

export const formatDate = (d, opts = { day: 'numeric', month: 'short', year: 'numeric' }) => {
  if (!d) return '—';
  const date = typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d) ? new Date(`${d}T12:00:00`) : new Date(d);
  return date.toLocaleDateString('en-GB', opts);
};

export const formatDateTime = (d) =>
  d ? new Date(d).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—';

/** YYYY-MM-DD in Kigali time (UTC+2), offset by `days`. */
export const kigaliDate = (days = 0) => new Date(Date.now() + 2 * 3600_000 + days * 86_400_000).toISOString().slice(0, 10);
export const kigaliHour = () => new Date(Date.now() + 2 * 3600_000).getUTCHours();

export const ORDER_STEPS = ['pending', 'confirmed', 'preparing', 'ready', 'out_for_delivery', 'delivered'];

export const ORDER_STATUS = {
  pending: { label: 'Pending', tone: 'amber' },
  confirmed: { label: 'Confirmed', tone: 'blue' },
  preparing: { label: 'Preparing', tone: 'lilac' },
  ready: { label: 'Ready', tone: 'teal' },
  out_for_delivery: { label: 'Out for delivery', tone: 'rose' },
  delivered: { label: 'Delivered', tone: 'green' },
  cancelled: { label: 'Cancelled', tone: 'gray' },
};

/** Next status a staff member can move an order to (cancellation handled separately). */
export const NEXT_ORDER_STATUS = {
  pending: 'confirmed',
  confirmed: 'preparing',
  preparing: 'ready',
  ready: 'out_for_delivery',
  out_for_delivery: 'delivered',
};

export const DELIVERY_STATUS = {
  pending: { label: 'Pending', tone: 'amber' },
  assigned: { label: 'Assigned', tone: 'blue' },
  picked_up: { label: 'Picked up', tone: 'lilac' },
  on_the_way: { label: 'On the way', tone: 'rose' },
  delivered: { label: 'Delivered', tone: 'green' },
};

export const PAYMENT_STATUS = {
  pending: { label: 'Pending', tone: 'amber' },
  paid: { label: 'Paid', tone: 'green' },
  failed: { label: 'Failed', tone: 'red' },
  refunded: { label: 'Refunded', tone: 'gray' },
};

export const PAYMENT_METHODS = {
  mtn_momo: 'MTN Mobile Money',
  airtel_money: 'Airtel Money',
  card: 'Visa / Mastercard',
  cash_on_delivery: 'Cash on Delivery',
};

export const STAFF_ROLES = {
  order_manager: 'Order Manager',
  delivery_staff: 'Delivery Staff',
  customer_support: 'Customer Support',
  inventory_staff: 'Inventory Staff',
};

export const MESSAGE_TYPES = [
  ['birthday', '🎂 Birthday', 'Happy Birthday! May your day be as beautiful as these flowers. 🌸'],
  ['romantic', '❤️ Romantic', 'Every petal is a reason I love you. Thinking of you always. ❤️'],
  ['congratulations', '🎉 Congratulations', 'Congratulations on your wonderful achievement! So proud of you. 🎉'],
  ['anniversary', '💞 Anniversary', 'Happy Anniversary! Here is to many more beautiful years together. 💞'],
  ['wedding', '💒 Wedding', 'Wishing you a lifetime of love and happiness on your wedding day. 💒'],
  ['thank_you', '🙏 Thank you', 'Thank you for everything - these flowers are a small token of my gratitude. 🌷'],
  ['custom', '✍️ Custom', ''],
];

export const initials = (first = '', last = '') => `${first?.[0] || ''}${last?.[0] || ''}`.toUpperCase();

export const homeFor = (role) => ({ admin: '/admin', staff: '/staff' })[role] || '/account';
