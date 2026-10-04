import mongoose from 'mongoose';

const { Schema } = mongoose;

/** Messages sent through the public "Contact Us" form, plus staff replies. */
const contactMessageSchema = new Schema(
  {
    name: { type: String, required: true, trim: true },
    email: { type: String, required: true, lowercase: true, trim: true },
    phone: String,
    subject: { type: String, required: true },
    message: { type: String, required: true },
    userId: Number, // set when a logged-in customer writes
    status: { type: String, enum: ['new', 'read', 'replied', 'closed'], default: 'new', index: true },
    replies: [
      {
        body: String,
        authorId: Number,
        authorName: String,
        at: { type: Date, default: Date.now },
      },
    ],
  },
  { timestamps: true },
);

/** Append-only audit trail of security-relevant and business actions. */
const auditLogSchema = new Schema(
  {
    event: { type: String, required: true, index: true }, // e.g. order.placed
    actorId: Number,
    entity: String,
    entityId: String,
    data: Schema.Types.Mixed,
  },
  { timestamps: { createdAt: true, updatedAt: false } },
);
auditLogSchema.index({ createdAt: -1 });

/** Every email / SMS the notification worker dispatched (outbox log). */
const notificationSchema = new Schema(
  {
    channel: { type: String, enum: ['email', 'sms'], required: true },
    to: { type: String, required: true },
    subject: String,
    body: String,
    event: String,
    userId: { type: Number, index: true },
    status: { type: String, enum: ['sent', 'failed', 'logged'], default: 'sent' },
    error: String,
  },
  { timestamps: { createdAt: true, updatedAt: false } },
);

export const ContactMessage =
  mongoose.models.ContactMessage || mongoose.model('ContactMessage', contactMessageSchema);
export const AuditLog = mongoose.models.AuditLog || mongoose.model('AuditLog', auditLogSchema);
export const Notification =
  mongoose.models.Notification || mongoose.model('Notification', notificationSchema);
