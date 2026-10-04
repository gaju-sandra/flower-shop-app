import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { api } from '../api/client';

/** Public storefront settings: contact details, delivery fee, time slots, gift options. */
const DEFAULTS = {
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
  social: { instagram: '', facebook: '', x: '', whatsapp: '' },
  giftOptions: [],
};

const SettingsContext = createContext(DEFAULTS);

export function SettingsProvider({ children }) {
  const [settings, setSettings] = useState(DEFAULTS);

  const reload = useCallback(() => {
    api
      .get('/settings/public')
      .then((r) => setSettings({ ...DEFAULTS, ...r.data }))
      .catch(() => {});
  }, []);

  useEffect(reload, [reload]);

  return <SettingsContext.Provider value={{ ...settings, reload }}>{children}</SettingsContext.Provider>;
}

export const useSettings = () => useContext(SettingsContext);
