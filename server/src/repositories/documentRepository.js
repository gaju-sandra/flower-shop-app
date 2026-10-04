import { randomUUID } from 'node:crypto';
import { isMongoReady } from '../db/mongo.js';
import { AuditLog, ContactMessage, Notification } from '../models/documents.js';

/**
 * Data-access layer for the document (NoSQL) side of the system.
 * Uses MongoDB when connected, otherwise a bounded in-memory store.
 */
const memory = { contact: [], audit: [], notifications: [] };
const MAX = 500;
const pushBounded = (arr, doc) => {
  arr.unshift(doc);
  if (arr.length > MAX) arr.length = MAX;
  return doc;
};
const memDoc = (data) => ({ _id: randomUUID(), createdAt: new Date(), updatedAt: new Date(), ...data });

export const contactRepo = {
  async create(data) {
    if (isMongoReady()) return (await ContactMessage.create(data)).toObject();
    return pushBounded(memory.contact, memDoc({ status: 'new', replies: [], ...data }));
  },
  async list({ status } = {}) {
    if (isMongoReady()) {
      const filter = status ? { status } : {};
      return ContactMessage.find(filter).sort({ createdAt: -1 }).limit(200).lean();
    }
    return memory.contact.filter((m) => !status || m.status === status);
  },
  async findById(id) {
    if (isMongoReady()) return ContactMessage.findById(id).lean().catch(() => null);
    return memory.contact.find((m) => m._id === id) || null;
  },
  async update(id, patch, reply) {
    if (isMongoReady()) {
      const update = { $set: patch };
      if (reply) update.$push = { replies: reply };
      return ContactMessage.findByIdAndUpdate(id, update, { new: true }).lean().catch(() => null);
    }
    const msg = memory.contact.find((m) => m._id === id);
    if (!msg) return null;
    Object.assign(msg, patch, { updatedAt: new Date() });
    if (reply) msg.replies.push({ ...reply, at: new Date() });
    return msg;
  },
  async countNew() {
    if (isMongoReady()) return ContactMessage.countDocuments({ status: 'new' });
    return memory.contact.filter((m) => m.status === 'new').length;
  },
};

export const auditRepo = {
  async record(entry) {
    if (isMongoReady()) return AuditLog.create(entry);
    return pushBounded(memory.audit, memDoc(entry));
  },
  async recent(limit = 50) {
    if (isMongoReady()) return AuditLog.find().sort({ createdAt: -1 }).limit(limit).lean();
    return memory.audit.slice(0, limit);
  },
};

export const notificationRepo = {
  async record(entry) {
    if (isMongoReady()) return Notification.create(entry);
    return pushBounded(memory.notifications, memDoc(entry));
  },
  async recent({ limit = 50, userId } = {}) {
    if (isMongoReady()) {
      const filter = userId ? { userId } : {};
      return Notification.find(filter).sort({ createdAt: -1 }).limit(limit).lean();
    }
    return memory.notifications.filter((n) => !userId || n.userId === userId).slice(0, limit);
  },
};
