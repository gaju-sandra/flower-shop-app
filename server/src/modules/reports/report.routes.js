import { Router } from 'express';
import { z } from 'zod';
import { authenticate, authorize } from '../../middleware/auth.js';
import { validate } from '../../middleware/validate.js';
import { brokerMode } from '../../messaging/broker.js';
import { isMongoReady } from '../../db/mongo.js';
import { auditRepo, notificationRepo } from '../../repositories/documentRepository.js';
import { PERMISSIONS as P } from '../../security/permissions.js';
import { asyncHandler } from '../../utils/http.js';
import * as reports from './report.service.js';

const router = Router();
router.use(authenticate);

router.get('/staff-dashboard', authorize(P.STAFF_DASHBOARD), asyncHandler(async (req, res) => res.json(await reports.staffDashboard(req.user.id))));

router.get('/admin-dashboard', authorize(P.REPORT_READ), asyncHandler(async (_req, res) => res.json(await reports.adminDashboard())));

router.get(
  '/sales',
  authorize(P.REPORT_READ),
  validate(z.object({ days: z.coerce.number().int().min(7).max(365).default(30) }), 'query'),
  asyncHandler(async (req, res) => res.json(await reports.salesOverTime(req.query.days))),
);

router.get(
  '/orders.csv',
  authorize(P.REPORT_READ),
  validate(z.object({ from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }), 'query'),
  asyncHandler(async (req, res) => {
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', `attachment; filename="orders-${req.query.from}-to-${req.query.to}.csv"`);
    res.send(await reports.ordersCsv(req.query.from, req.query.to));
  }),
);

// Document-store views (MongoDB): audit trail + notification outbox.
router.get(
  '/activity',
  authorize(P.AUDIT_READ),
  asyncHandler(async (_req, res) => {
    const [audit, notifications] = await Promise.all([auditRepo.recent(60), notificationRepo.recent({ limit: 60 })]);
    res.json({ audit, notifications, infrastructure: { documentStore: isMongoReady() ? 'mongodb' : 'in-memory', broker: brokerMode() } });
  }),
);

export default router;
