import { query } from '../../db/postgres.js';
import { contactRepo } from '../../repositories/documentRepository.js';

const REVENUE = `status <> 'cancelled'`;

export async function adminDashboard() {
  const [{ rows: k }, sales, popular, byMonth, delivery, byCategory, payMix, lowStock] = await Promise.all([
    query(
      `SELECT
         (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE ${REVENUE}) AS total_sales,
         (SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE ${REVENUE} AND created_at >= date_trunc('month', NOW())) AS month_sales,
         (SELECT COUNT(*) FROM orders) AS total_orders,
         (SELECT COUNT(*) FROM users WHERE role = 'customer') AS total_customers,
         (SELECT COUNT(*) FROM products) AS total_products,
         (SELECT COUNT(*) FROM orders WHERE status IN ('pending','confirmed','preparing','ready','out_for_delivery')) AS pending_orders,
         (SELECT COUNT(*) FROM orders WHERE status = 'delivered') AS delivered_orders,
         (SELECT COUNT(*) FROM users WHERE role = 'staff') AS total_staff,
         (SELECT COALESCE(AVG(total_amount),0) FROM orders WHERE ${REVENUE}) AS avg_order_value`,
    ),
    salesOverTime(30),
    popularFlowers(6),
    ordersByMonth(12),
    deliveryPerformance(),
    revenueByCategory(),
    query(
      `SELECT payment_method AS method, COUNT(*) AS orders, COALESCE(SUM(total_amount),0) AS amount
       FROM orders WHERE ${REVENUE} GROUP BY payment_method ORDER BY amount DESC`,
    ),
    query(`SELECT id, name, stock FROM products WHERE stock <= 5 AND status = 'active' ORDER BY stock LIMIT 6`),
  ]);
  const s = k[0];
  return {
    stats: {
      totalSales: s.total_sales, monthSales: s.month_sales, totalOrders: s.total_orders,
      totalCustomers: s.total_customers, totalProducts: s.total_products, pendingOrders: s.pending_orders,
      deliveredOrders: s.delivered_orders, totalStaff: s.total_staff, avgOrderValue: Math.round(s.avg_order_value),
      newMessages: await contactRepo.countNew(),
    },
    salesOverTime: sales,
    popularFlowers: popular,
    ordersByMonth: byMonth,
    deliveryPerformance: delivery,
    revenueByCategory: byCategory,
    paymentMix: payMix.rows,
    lowStock: lowStock.rows,
  };
}

export async function salesOverTime(days = 30) {
  const { rows } = await query(
    `SELECT to_char(d, 'YYYY-MM-DD') AS date,
            COALESCE(SUM(o.total_amount), 0) AS revenue, COUNT(o.id) AS orders
     FROM generate_series(CURRENT_DATE - ($1::int - 1), CURRENT_DATE, INTERVAL '1 day') d
     LEFT JOIN orders o ON o.created_at::date = d::date AND o.${REVENUE}
     GROUP BY d ORDER BY d`,
    [days],
  );
  return rows;
}

export async function ordersByMonth(months = 12) {
  const { rows } = await query(
    `SELECT to_char(m, 'Mon YY') AS month, COUNT(o.id) AS orders, COALESCE(SUM(o.total_amount) FILTER (WHERE o.${REVENUE}), 0) AS revenue
     FROM generate_series(date_trunc('month', NOW()) - (($1::int - 1) || ' months')::interval, date_trunc('month', NOW()), INTERVAL '1 month') m
     LEFT JOIN orders o ON date_trunc('month', o.created_at) = m
     GROUP BY m ORDER BY m`,
    [months],
  );
  return rows;
}

export async function popularFlowers(limit = 6) {
  const { rows } = await query(
    `SELECT oi.product_name AS name, SUM(oi.quantity) AS quantity, SUM(oi.quantity * oi.price) AS revenue
     FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.${REVENUE}
     GROUP BY oi.product_name ORDER BY quantity DESC LIMIT $1`,
    [limit],
  );
  return rows;
}

export async function revenueByCategory() {
  const { rows } = await query(
    `SELECT COALESCE(c.name, 'Uncategorised') AS category, SUM(oi.quantity * oi.price) AS revenue
     FROM order_items oi JOIN orders o ON o.id = oi.order_id
     LEFT JOIN products p ON p.id = oi.product_id LEFT JOIN categories c ON c.id = p.category_id
     WHERE o.${REVENUE} GROUP BY c.name ORDER BY revenue DESC`,
  );
  return rows;
}

export async function deliveryPerformance() {
  const { rows } = await query(
    `SELECT
       COUNT(*) FILTER (WHERE d.delivery_status = 'delivered') AS delivered,
       COUNT(*) FILTER (WHERE d.delivery_status = 'delivered' AND d.delivered_at::date <= d.delivery_date) AS on_time,
       COUNT(*) FILTER (WHERE d.delivery_status IN ('assigned','picked_up','on_the_way')) AS in_progress,
       COUNT(*) FILTER (WHERE d.delivery_status = 'pending') AS unassigned,
       COUNT(*) FILTER (WHERE d.delivery_status <> 'delivered' AND d.delivery_date < CURRENT_DATE) AS late
     FROM deliveries d JOIN orders o ON o.id = d.order_id WHERE o.status <> 'cancelled'`,
  );
  const r = rows[0];
  return { ...r, onTimeRate: r.delivered ? Math.round((r.on_time / r.delivered) * 100) : 100 };
}

export async function staffDashboard(staffId) {
  const [{ rows }, { rows: today }, { rows: mine }] = await Promise.all([
    query(
      `SELECT
         COUNT(*) FILTER (WHERE status = 'pending') AS new_orders,
         COUNT(*) FILTER (WHERE status IN ('confirmed','preparing')) AS preparing,
         COUNT(*) FILTER (WHERE status = 'ready') AS ready,
         COUNT(*) FILTER (WHERE status = 'out_for_delivery') AS out_for_delivery,
         COUNT(*) FILTER (WHERE status = 'delivered') AS completed,
         COUNT(*) FILTER (WHERE status = 'delivered' AND updated_at::date = CURRENT_DATE) AS completed_today
       FROM orders`,
    ),
    query(
      `SELECT d.id, d.delivery_time, d.delivery_status, d.delivery_address, o.order_number, o.customer_name, o.id AS order_id,
              u.first_name AS staff_first
       FROM deliveries d JOIN orders o ON o.id = d.order_id LEFT JOIN users u ON u.id = d.staff_id
       WHERE d.delivery_date = CURRENT_DATE AND o.status <> 'cancelled' ORDER BY d.delivery_time`,
    ),
    query(
      `SELECT COUNT(*) AS n FROM deliveries WHERE staff_id = $1 AND delivery_status IN ('assigned','picked_up','on_the_way')`,
      [staffId],
    ),
  ]);
  const s = rows[0];
  return {
    stats: {
      newOrders: s.new_orders, preparing: s.preparing, ready: s.ready, outForDelivery: s.out_for_delivery,
      completed: s.completed, completedToday: s.completed_today, todaysDeliveries: today.length,
      myActiveDeliveries: mine[0].n, newMessages: await contactRepo.countNew(),
    },
    todaysDeliveries: today.map((d) => ({
      id: d.id, orderId: d.order_id, orderNumber: d.order_number, customer: d.customer_name, time: d.delivery_time,
      status: d.delivery_status, address: d.delivery_address, courier: d.staff_first,
    })),
  };
}

/** Flat CSV of orders between two dates - for the Reports page export. */
export async function ordersCsv(from, to) {
  const { rows } = await query(
    `SELECT order_number, created_at::date AS date, customer_name, phone, status, payment_method, payment_status,
            subtotal, discount, delivery_fee, gift_total, total_amount, delivery_date
     FROM orders WHERE created_at::date BETWEEN $1 AND $2 ORDER BY created_at`,
    [from, to],
  );
  const header = Object.keys(rows[0] || { order_number: 0 });
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  return [header.join(','), ...rows.map((r) => header.map((h) => esc(r[h])).join(','))].join('\n');
}
