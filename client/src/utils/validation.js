/** Client-side checks mirroring the API's zod rules (the server stays the source of truth). */
export const RW_PHONE = /^(\+?250|0)7[2389]\d{7}$/;
export const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export const cleanPhone = (s = '') => String(s).replace(/[\s-]/g, '');

export function passwordProblems(pw = '') {
  const problems = [];
  if (pw.length < 8) problems.push('at least 8 characters');
  if (!/[a-z]/.test(pw)) problems.push('a lowercase letter');
  if (!/[A-Z]/.test(pw)) problems.push('an uppercase letter');
  if (!/\d/.test(pw)) problems.push('a number');
  return problems;
}

/** 0 (empty) .. 5 (strong) */
export function passwordStrength(pw = '') {
  if (!pw) return 0;
  let score = 4 - passwordProblems(pw).length;
  if (pw.length >= 12) score += 1;
  if (/[^A-Za-z0-9]/.test(pw)) score += 1;
  return Math.max(1, Math.min(score, 5));
}

/** rules: { field: (value, allValues) => errorMessage | undefined } */
export function validateForm(values, rules) {
  const errors = {};
  for (const [field, rule] of Object.entries(rules)) {
    const msg = rule(values[field], values);
    if (msg) errors[field] = msg;
  }
  return errors;
}

export const required = (label) => (v) => (!String(v ?? '').trim() ? `${label} is required` : undefined);
export const minLength = (label, n) => (v) =>
  String(v ?? '').trim().length < n ? `${label} must be at least ${n} characters` : undefined;
export const phoneRule = (v) =>
  !RW_PHONE.test(cleanPhone(v)) ? 'Enter a valid Rwandan phone number, e.g. 0788123456' : undefined;
export const optionalPhoneRule = (v) => (v ? phoneRule(v) : undefined);
export const emailRule = (v) => (!EMAIL.test(String(v || '').trim()) ? 'Enter a valid email address' : undefined);
export const passwordRule = (v) => {
  const p = passwordProblems(v || '');
  return p.length ? `Password needs ${p.join(', ')}` : undefined;
};
