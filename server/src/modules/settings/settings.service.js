import { query } from '../../db/postgres.js';
import { cached, invalidate } from '../../utils/cache.js';

export const DEFAULT_SETTINGS = {
  store: {
    name: 'Bloom & Co.',
    phone: '+250 788 123 456',
    email: 'hello@bloomandco.rw',
    address: 'KG 7 Ave, Kacyiru, Kigali, Rwanda',
    hours: 'Mon – Sat: 8:00 – 20:00 · Sun: 9:00 – 17:00',
  },
  delivery: {
    deliveryFee: 2000,
    freeDeliveryThreshold: 50000,
    timeSlots: ['08:00 - 10:00', '10:00 - 12:00', '12:00 - 14:00', '14:00 - 16:00', '16:00 - 18:00', '18:00 - 20:00'],
    sameDayCutoffHour: 14,
  },
  social: {
    instagram: 'https://instagram.com',
    facebook: 'https://facebook.com',
    x: 'https://x.com',
    whatsapp: 'https://wa.me/250788123456',
  },
};

export const getSettings = () =>
  cached('settings:all', 60_000, async () => {
    const { rows } = await query('SELECT key, value FROM settings');
    const merged = structuredClone(DEFAULT_SETTINGS);
    for (const r of rows) merged[r.key] = { ...(merged[r.key] || {}), ...r.value };
    return merged;
  });

export async function updateSettings(section, value) {
  await query(
    `INSERT INTO settings (key, value) VALUES ($1, $2)
     ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()`,
    [section, value],
  );
  invalidate('settings:');
  return getSettings();
}

export const listGiftOptions = (includeInactive = false) =>
  cached(`settings:gifts:${includeInactive}`, 60_000, async () => {
    const { rows } = await query(
      `SELECT * FROM gift_options ${includeInactive ? '' : 'WHERE active'} ORDER BY id`,
    );
    return rows.map((g) => ({ id: g.id, code: g.code, name: g.name, icon: g.icon, price: g.price, active: g.active }));
  });

export async function updateGiftOption(id, { price, active }) {
  await query('UPDATE gift_options SET price = COALESCE($1, price), active = COALESCE($2, active) WHERE id = $3', [
    price ?? null,
    active ?? null,
    id,
  ]);
  invalidate('settings:');
}
