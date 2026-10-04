import compression from 'compression';
import cookieParser from 'cookie-parser';
import cors from 'cors';
import express from 'express';
import helmet from 'helmet';
import morgan from 'morgan';
import { env } from './config/env.js';
import { isMongoReady } from './db/mongo.js';
import { pool } from './db/postgres.js';
import { brokerMode } from './messaging/broker.js';
import { errorHandler, notFoundHandler } from './middleware/errors.js';
import { UPLOAD_DIR } from './middleware/upload.js';
import authRoutes from './modules/auth/auth.routes.js';
import cartRoutes from './modules/cart/cart.routes.js';
import contactRoutes from './modules/contact/contact.routes.js';
import deliveryRoutes from './modules/deliveries/delivery.routes.js';
import orderRoutes from './modules/orders/order.routes.js';
import paymentRoutes from './modules/payments/payment.routes.js';
import productRoutes from './modules/products/product.routes.js';
import promotionRoutes from './modules/promotions/promotion.routes.js';
import reportRoutes from './modules/reports/report.routes.js';
import reviewRoutes from './modules/reviews/review.routes.js';
import settingsRoutes from './modules/settings/settings.routes.js';
import { addressRouter, customerRouter, profileRouter, staffRouter } from './modules/users/user.routes.js';
import wishlistRoutes from './modules/wishlist/wishlist.routes.js';

/**
 * Layered architecture:
 *   HTTP layer      routes (+ validation & RBAC middleware)
 *   Service layer   modules/*\/*.service.js - business rules, transactions
 *   Data layer      db/postgres.js (relational), repositories/documentRepository.js (MongoDB)
 *   Messaging       messaging/broker.js (RabbitMQ) -> workers/notificationWorker.js
 */
export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1);

  app.use(
    helmet({
      crossOriginResourcePolicy: { policy: 'cross-origin' }, // let the SPA load /uploads images
      contentSecurityPolicy: false, // API only; the SPA sets its own CSP
    }),
  );
  app.use(cors({ origin: env.clientUrl, credentials: true }));
  app.use(compression());
  app.use(express.json({ limit: '200kb' }));
  app.use(cookieParser(env.jwtRefreshSecret));
  if (!env.isTest) app.use(morgan(env.isProd ? 'combined' : 'dev'));

  app.use('/uploads', express.static(UPLOAD_DIR, { maxAge: '7d', immutable: true }));

  app.get('/api/health', async (_req, res) => {
    let postgres = 'up';
    try {
      await pool.query('SELECT 1');
    } catch {
      postgres = 'down';
    }
    res.status(postgres === 'up' ? 200 : 503).json({
      status: postgres === 'up' ? 'ok' : 'degraded',
      postgres,
      documentStore: isMongoReady() ? 'mongodb' : 'in-memory',
      broker: brokerMode(),
      uptime: Math.round(process.uptime()),
    });
  });

  app.use('/api/auth', authRoutes);
  app.use('/api/products', productRoutes);
  app.use('/api/cart', cartRoutes);
  app.use('/api/wishlist', wishlistRoutes);
  app.use('/api/orders', orderRoutes);
  app.use('/api/deliveries', deliveryRoutes);
  app.use('/api/payments', paymentRoutes);
  app.use('/api/reviews', reviewRoutes);
  app.use('/api/promotions', promotionRoutes);
  app.use('/api/profile', profileRouter);
  app.use('/api/addresses', addressRouter);
  app.use('/api/customers', customerRouter);
  app.use('/api/staff', staffRouter);
  app.use('/api/reports', reportRoutes);
  app.use('/api/settings', settingsRoutes);
  app.use('/api/contact', contactRoutes);

  app.use('/api', notFoundHandler);
  app.use(errorHandler);
  return app;
}
