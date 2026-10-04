/**
 * Role-Based Access Control model.
 *
 * Every protected route declares the *permission* it needs, never a role.
 * Roles are mapped to permissions here, in one place, so the policy can be
 * reviewed (and tested) independently from the routes.
 */
export const ROLES = Object.freeze({ CUSTOMER: 'customer', STAFF: 'staff', ADMIN: 'admin' });

export const PERMISSIONS = Object.freeze({
  // customer self-service
  CART_MANAGE: 'cart:manage',
  WISHLIST_MANAGE: 'wishlist:manage',
  ORDER_CREATE: 'orders:create',
  ORDER_READ_OWN: 'orders:read:own',
  REVIEW_CREATE: 'reviews:create',
  ADDRESS_MANAGE: 'addresses:manage',
  PROFILE_MANAGE: 'profile:manage',

  // operations (staff + admin)
  ORDER_READ_ANY: 'orders:read:any',
  ORDER_UPDATE_STATUS: 'orders:update-status',
  DELIVERY_READ: 'deliveries:read',
  DELIVERY_MANAGE: 'deliveries:manage',
  CUSTOMER_READ: 'customers:read',
  PRODUCT_READ_ADMIN: 'products:read-admin',
  PRODUCT_UPDATE_STOCK: 'products:update-stock',
  MESSAGE_READ: 'messages:read',
  MESSAGE_REPLY: 'messages:reply',
  STAFF_DASHBOARD: 'dashboard:staff',

  // administration only
  PRODUCT_MANAGE: 'products:manage',
  CATEGORY_MANAGE: 'categories:manage',
  CUSTOMER_MANAGE: 'customers:manage',
  STAFF_MANAGE: 'staff:manage',
  PAYMENT_READ: 'payments:read',
  PAYMENT_MANAGE: 'payments:manage',
  PROMOTION_MANAGE: 'promotions:manage',
  REPORT_READ: 'reports:read',
  REVIEW_MODERATE: 'reviews:moderate',
  SETTINGS_MANAGE: 'settings:manage',
  AUDIT_READ: 'audit:read',
});

const P = PERMISSIONS;

const CUSTOMER = [
  P.CART_MANAGE, P.WISHLIST_MANAGE, P.ORDER_CREATE, P.ORDER_READ_OWN,
  P.REVIEW_CREATE, P.ADDRESS_MANAGE, P.PROFILE_MANAGE,
];

const STAFF = [
  P.PROFILE_MANAGE, P.ORDER_READ_ANY, P.ORDER_UPDATE_STATUS, P.DELIVERY_READ, P.DELIVERY_MANAGE,
  P.CUSTOMER_READ, P.PRODUCT_READ_ADMIN, P.PRODUCT_UPDATE_STOCK, P.MESSAGE_READ, P.MESSAGE_REPLY,
  P.STAFF_DASHBOARD,
];

// Administrators inherit every staff permission plus the administration set.
const ADMIN = [
  ...STAFF,
  P.PRODUCT_MANAGE, P.CATEGORY_MANAGE, P.CUSTOMER_MANAGE, P.STAFF_MANAGE, P.PAYMENT_READ,
  P.PAYMENT_MANAGE, P.PROMOTION_MANAGE, P.REPORT_READ, P.REVIEW_MODERATE, P.SETTINGS_MANAGE,
  P.AUDIT_READ,
];

export const ROLE_PERMISSIONS = Object.freeze({
  [ROLES.CUSTOMER]: new Set(CUSTOMER),
  [ROLES.STAFF]: new Set(STAFF),
  [ROLES.ADMIN]: new Set(ADMIN),
});

export const hasPermission = (role, permission) => ROLE_PERMISSIONS[role]?.has(permission) ?? false;

export const permissionsFor = (role) => [...(ROLE_PERMISSIONS[role] ?? [])];
