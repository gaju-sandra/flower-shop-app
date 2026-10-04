import { env } from '../config/env.js';
import { auditRepo } from '../repositories/documentRepository.js';
import { sendEmail, sendSms } from './notifiers.js';

const money = (n) => `RWF ${Number(n || 0).toLocaleString('en-US')}`;
const STATUS_TEXT = {
  confirmed: 'has been confirmed ✅',
  preparing: 'is being prepared by our florists 💐',
  ready: 'is ready for delivery 📦',
  out_for_delivery: 'is out for delivery 🚚',
  delivered: 'has been delivered. Enjoy your flowers! 🌸',
  cancelled: 'has been cancelled',
};

/** Builds the email for an event, or null if the event has no email. */
function emailFor({ event, data }) {
  switch (event) {
    case 'user.registered':
      return {
        to: data.email,
        subject: 'Welcome to Bloom & Co. 🌸',
        html: `<p>Hi ${data.firstName},</p><p>Your account is ready. Fresh flowers for every moment are just a click away.</p>
               <p><a href="${env.clientUrl}/products">Browse our collection →</a></p>`,
      };
    case 'staff.created':
      return {
        to: data.email,
        subject: 'Your Bloom & Co. staff account',
        html: `<p>Hi ${data.firstName},</p><p>An administrator created a staff account for you (${data.staffRole || 'staff'}).
               Sign in at <a href="${env.clientUrl}/login">${env.clientUrl}/login</a> with the password you were given, then change it from your profile.</p>`,
      };
    case 'auth.password_reset_requested':
      return {
        to: data.email,
        subject: 'Reset your password',
        html: `<p>Hi ${data.firstName},</p><p>Click the link below to choose a new password. It expires in 30 minutes.</p>
               <p><a href="${data.resetUrl}">Reset my password</a></p><p>If you did not request this, you can ignore this email.</p>`,
      };
    case 'order.placed':
      return {
        to: data.email,
        subject: `Order ${data.orderNumber} received 🌷`,
        html: `<p>Hi ${data.customerName},</p><p>Thank you for your order! Here is a summary:</p>
               <ul>${data.items.map((i) => `<li>${i.quantity} × ${i.name}</li>`).join('')}</ul>
               <p><b>Total:</b> ${money(data.total)}<br/><b>Delivery:</b> ${data.deliveryDate} (${data.deliveryTime})<br/>
               <b>Address:</b> ${data.address}</p>
               <p><a href="${env.clientUrl}/account/orders/${data.orderId}">Track your order →</a></p>`,
      };
    case 'order.status_changed':
      if (!STATUS_TEXT[data.status]) return null;
      return {
        to: data.email,
        subject: `Order ${data.orderNumber} update`,
        html: `<p>Hi ${data.customerName},</p><p>Your order <b>${data.orderNumber}</b> ${STATUS_TEXT[data.status]}</p>
               <p><a href="${env.clientUrl}/account/orders/${data.orderId}">View order →</a></p>`,
      };
    case 'payment.completed':
      return {
        to: data.email,
        subject: `Payment received for ${data.orderNumber}`,
        html: `<p>We received your payment of <b>${money(data.amount)}</b> via ${data.method}.<br/>Reference: ${data.reference}</p>`,
      };
    case 'contact.received':
      return {
        to: data.email,
        subject: 'We received your message 💌',
        html: `<p>Hi ${data.name},</p><p>Thanks for reaching out about “${data.subject}”. Our team will reply within one business day.</p>`,
      };
    case 'contact.replied':
      return {
        to: data.email,
        subject: `Re: ${data.subject}`,
        html: `<p>Hi ${data.name},</p><p>${String(data.reply).replace(/\n/g, '<br/>')}</p><p>— ${data.staffName}, Bloom &amp; Co.</p>`,
      };
    default:
      return null;
  }
}

function smsFor({ event, data }) {
  if (event === 'order.placed') {
    return `Bloom&Co: Order ${data.orderNumber} received. Total ${money(data.total)}. Delivery ${data.deliveryDate}.`;
  }
  if (event === 'order.status_changed' && STATUS_TEXT[data.status]) {
    return `Bloom&Co: Your order ${data.orderNumber} ${STATUS_TEXT[data.status].replace(/[^\x20-\x7E]/g, '').trim()}.`;
  }
  if (event === 'delivery.assigned' && data.staffPhone) {
    return `Bloom&Co: Delivery ${data.orderNumber} assigned to you for ${data.deliveryDate} (${data.deliveryTime}) - ${data.address}.`;
  }
  return null;
}

export async function handleEmail(message) {
  const mail = emailFor(message);
  if (mail) await sendEmail({ ...mail, event: message.event, userId: message.data.userId });
}

export async function handleSms(message) {
  const body = smsFor(message);
  if (!body) return;
  const to = message.event === 'delivery.assigned' ? message.data.staffPhone : message.data.phone;
  await sendSms({ to, body, event: message.event, userId: message.data.userId });
}

export async function handleAudit(message) {
  const { actorId, entity, entityId, ...rest } = message.data || {};
  // never persist secrets in the audit trail
  delete rest.resetUrl;
  await auditRepo.record({
    event: message.event,
    actorId,
    entity,
    entityId: entityId != null ? String(entityId) : undefined,
    data: rest,
  });
}
