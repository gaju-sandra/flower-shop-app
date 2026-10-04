// Generates bloom-api.postman_collection.json:  node docs/postman/build-collection.mjs
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const saveToken = [
  'const body = pm.response.json();',
  "if (body.accessToken) { pm.collectionVariables.set('accessToken', body.accessToken); }",
  "pm.test('status is 2xx', () => pm.expect(pm.response.code).to.be.within(200, 299));",
];
const ok = ["pm.test('status is 2xx', () => pm.expect(pm.response.code).to.be.within(200, 299));"];
const forbidden = ["pm.test('RBAC: forbidden (403)', () => pm.response.to.have.status(403));"];

function req(name, method, path, { body, auth = true, tests = ok, description, query } = {}) {
  const [rawPath] = path.split('?');
  return {
    name,
    event: [{ listen: 'test', script: { type: 'text/javascript', exec: tests } }],
    request: {
      method,
      description,
      auth: auth ? { type: 'bearer', bearer: [{ key: 'token', value: '{{accessToken}}', type: 'string' }] } : { type: 'noauth' },
      header: body ? [{ key: 'Content-Type', value: 'application/json' }] : [],
      body: body ? { mode: 'raw', raw: JSON.stringify(body, null, 2) } : undefined,
      url: {
        raw: `{{baseUrl}}${path}`,
        host: ['{{baseUrl}}'],
        path: rawPath.split('/').filter(Boolean),
        query: query?.map(([key, value]) => ({ key, value })),
      },
    },
  };
}

const folder = (name, item, description) => ({ name, item, description });

const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);

const collection = {
  info: {
    name: 'Bloom & Co. Flower Shop API',
    description:
      'Run "1. Auth" logins first: each login stores the JWT in the {{accessToken}} collection variable, ' +
      'so every following request is sent as that user. Log in as a different role to test RBAC.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  variable: [
    { key: 'baseUrl', value: 'http://localhost:5000/api' },
    { key: 'accessToken', value: '' },
  ],
  item: [
    folder('0. Health', [req('Health check', 'GET', '/health', { auth: false })]),
    folder('1. Auth', [
      req('Login as customer (Melissa)', 'POST', '/auth/login', {
        auth: false, tests: saveToken, body: { email: 'melissa@example.com', password: 'Customer@123', remember: true },
      }),
      req('Login as staff (Aline)', 'POST', '/auth/login', {
        auth: false, tests: saveToken, body: { email: 'aline.staff@bloomandco.rw', password: 'Staff@123' },
      }),
      req('Login as admin', 'POST', '/auth/login', {
        auth: false, tests: saveToken, body: { email: 'admin@bloomandco.rw', password: 'Admin@123' },
      }),
      req('Register a new customer', 'POST', '/auth/register', {
        auth: false,
        tests: saveToken,
        body: {
          firstName: 'Test', lastName: 'Customer', email: 'test.customer@example.com', phone: '0788555111',
          password: 'Flowers@2026', confirmPassword: 'Flowers@2026',
          address: { province: 'Kigali City', district: 'Gasabo', sector: 'Remera', street: 'KG 11 Ave, House 5' },
        },
      }),
      req('Register with invalid data (expect 400)', 'POST', '/auth/register', {
        auth: false,
        tests: ["pm.test('validation error', () => pm.response.to.have.status(400));"],
        body: { firstName: 'A', lastName: '', email: 'not-an-email', phone: '123', password: 'weak', confirmPassword: 'x' },
      }),
      req('Who am I', 'GET', '/auth/me'),
      req('Refresh session (uses cookie)', 'POST', '/auth/refresh', { auth: false, tests: saveToken }),
      req('Forgot password', 'POST', '/auth/forgot-password', { auth: false, body: { email: 'melissa@example.com' } }),
      req('OAuth providers', 'GET', '/auth/oauth/providers', { auth: false }),
      req('Logout', 'POST', '/auth/logout', { auth: false }),
    ]),
    folder('2. Catalogue (public)', [
      req('List products', 'GET', '/products?sort=popular&limit=12', { auth: false, query: [['sort', 'popular'], ['limit', '12']] }),
      req('Search & filter products', 'GET', '/products?search=rose&minPrice=5000&maxPrice=30000&sort=price_asc', {
        auth: false, query: [['search', 'rose'], ['minPrice', '5000'], ['maxPrice', '30000'], ['sort', 'price_asc']],
      }),
      req('Categories', 'GET', '/products/categories', { auth: false }),
      req('Product detail by slug', 'GET', '/products/red-roses', { auth: false }),
      req('Active promotions', 'GET', '/promotions/active', { auth: false }),
      req('Public settings & gift options', 'GET', '/settings/public', { auth: false }),
      req('Send contact message', 'POST', '/contact', {
        auth: false,
        body: { name: 'Postman Tester', email: 'tester@example.com', phone: '0788123456', subject: 'Wedding flowers', message: 'Do you deliver bridal bouquets to Musanze?' },
      }),
    ]),
    folder('3. Customer (login as customer first)', [
      req('Dashboard summary', 'GET', '/profile/dashboard'),
      req('Get cart', 'GET', '/cart'),
      req('Add to cart', 'POST', '/cart/items', { body: { productId: 1, quantity: 2 } }),
      req('Change quantity', 'PATCH', '/cart/items/1', { body: { quantity: 3 } }),
      req('Wishlist: add', 'POST', '/wishlist/2'),
      req('Wishlist: list', 'GET', '/wishlist'),
      req('Saved addresses', 'GET', '/addresses'),
      req('Checkout quote (gifts + promo)', 'POST', '/orders/quote', { body: { giftOptions: ['gift_wrap', 'greeting_card'], promoCode: 'BLOOM10' } }),
      req('Place order (MTN MoMo)', 'POST', '/orders', {
        tests: [
          "pm.test('order created', () => pm.response.to.have.status(201));",
          "pm.collectionVariables.set('orderId', pm.response.json().order.id);",
        ],
        body: {
          contact: { fullName: 'Melissa Ineza', phone: '0788111222', email: 'melissa@example.com' },
          delivery: {
            province: 'Kigali City', district: 'Gasabo', sector: 'Kimihurura', street: 'KG 9 Ave, House 12',
            locationDescription: 'Blue gate next to the pharmacy', date: tomorrow, time: '10:00 - 12:00',
          },
          instructions: 'Please call me when you arrive.',
          message: { recipientName: 'Sarah', type: 'birthday', text: 'Happy Birthday Sarah! ❤️' },
          giftOptions: ['gift_wrap'],
          promoCode: 'BLOOM10',
          payment: { method: 'mtn_momo', phone: '0788111222' },
        },
        description: 'Add something to the cart first. Delivery date must be today/after; change it if the request fails.',
      }),
      req('My orders', 'GET', '/orders/mine'),
      req('Track order', 'GET', '/orders/{{orderId}}'),
      req('Cancel my order (only while pending)', 'POST', '/orders/{{orderId}}/cancel'),
      req('Products I can review', 'GET', '/reviews/mine/reviewable'),
      req('RBAC check: customer opens admin report (expect 403)', 'GET', '/reports/admin-dashboard', { tests: forbidden }),
    ]),
    folder('4. Staff (login as staff first)', [
      req('Staff dashboard', 'GET', '/reports/staff-dashboard'),
      req('All orders (pending)', 'GET', '/orders?status=pending', { query: [['status', 'pending']] }),
      req('Confirm an order', 'PATCH', '/orders/{{orderId}}/status', { body: { status: 'confirmed', note: 'Confirmed by phone' } }),
      req('Deliveries today', 'GET', `/deliveries?date=${tomorrow}`, { query: [['date', tomorrow]] }),
      req('Couriers', 'GET', '/deliveries/couriers'),
      req('Customers', 'GET', '/customers?search=melissa', { query: [['search', 'melissa']] }),
      req('Contact messages', 'GET', '/contact'),
      req('Update stock', 'PATCH', '/products/1/stock', { body: { stock: 50 } }),
      req('RBAC check: staff creates product (expect 403)', 'POST', '/products', {
        tests: forbidden, body: { name: 'Should fail', price: 1000, stock: 1 },
      }),
    ]),
    folder('5. Admin (login as admin first)', [
      req('Admin dashboard + charts data', 'GET', '/reports/admin-dashboard'),
      req('Sales over 90 days', 'GET', '/reports/sales?days=90', { query: [['days', '90']] }),
      req('Activity log (MongoDB) + infrastructure', 'GET', '/reports/activity'),
      req('All products incl. inactive', 'GET', '/products/admin/all'),
      req('Create product', 'POST', '/products', {
        tests: ["pm.test('created', () => pm.response.to.have.status(201));", "pm.collectionVariables.set('productId', pm.response.json().id);"],
        body: { name: 'Postman Peonies', description: 'Created from Postman', categoryId: 1, price: 21000, discountPercent: 5, stock: 10, occasions: ['wedding'], status: 'active' },
      }),
      req('Update product', 'PUT', '/products/{{productId}}', { body: { price: 19500, stock: 12 } }),
      req('Delete product', 'DELETE', '/products/{{productId}}'),
      req('Create category', 'POST', '/products/categories', { body: { name: 'Sympathy Flowers', description: 'Gentle arrangements' } }),
      req('Staff list', 'GET', '/staff'),
      req('Add staff member', 'POST', '/staff', {
        body: { firstName: 'Postman', lastName: 'Courier', email: 'postman.courier@bloomandco.rw', phone: '0788000199', password: 'Courier@2026', staffRole: 'delivery_staff' },
      }),
      req('Payments', 'GET', '/payments'),
      req('Promotions', 'GET', '/promotions'),
      req('Create promotion', 'POST', '/promotions', {
        body: { code: 'SPRING25', title: 'Spring sale 25%', discountPercent: 25, minOrder: 15000, startsAt: new Date().toISOString().slice(0, 10), endsAt: null, active: true },
      }),
      req('Reviews', 'GET', '/reviews'),
      req('Settings', 'GET', '/settings'),
      req('Update delivery settings', 'PUT', '/settings/delivery', {
        body: { deliveryFee: 2500, freeDeliveryThreshold: 50000, timeSlots: ['08:00 - 10:00', '10:00 - 12:00', '12:00 - 14:00', '14:00 - 16:00', '16:00 - 18:00', '18:00 - 20:00'], sameDayCutoffHour: 14 },
      }),
    ]),
  ],
};

const out = join(dirname(fileURLToPath(import.meta.url)), 'bloom-api.postman_collection.json');
writeFileSync(out, `${JSON.stringify(collection, null, 2)}\n`);
console.log(`wrote ${out}`);
