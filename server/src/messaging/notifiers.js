import nodemailer from 'nodemailer';
import { env } from '../config/env.js';
import { notificationRepo } from '../repositories/documentRepository.js';

const transporter = env.smtp.host
  ? nodemailer.createTransport({
      host: env.smtp.host,
      port: env.smtp.port,
      secure: env.smtp.port === 465,
      auth: env.smtp.user ? { user: env.smtp.user, pass: env.smtp.pass } : undefined,
    })
  : nodemailer.createTransport({ jsonTransport: true }); // dev: render but don't send

const wrapHtml = (title, body) => `
<div style="font-family:Georgia,serif;max-width:560px;margin:auto;background:#fffaf5;border-radius:16px;overflow:hidden;border:1px solid #f3d9e0">
  <div style="background:linear-gradient(135deg,#e11d74,#c084fc);padding:24px;color:#fff;font-size:22px">🌸 Bloom &amp; Co.</div>
  <div style="padding:24px;color:#3f2a33;font-size:15px;line-height:1.6">
    <h2 style="color:#be185d;margin-top:0">${title}</h2>${body}
  </div>
  <div style="padding:16px 24px;font-size:12px;color:#9b7b86;background:#fdf2f6">Beautiful flowers, delivered with love · Kigali, Rwanda</div>
</div>`;

export async function sendEmail({ to, subject, html, event, userId }) {
  if (!to) return;
  try {
    await transporter.sendMail({ from: env.smtp.from, to, subject, html: wrapHtml(subject, html) });
    const status = env.smtp.host ? 'sent' : 'logged';
    if (!env.smtp.host && !env.isTest) console.log(`📧 [email:${event}] to=${to} subject="${subject}"`);
    await notificationRepo.record({ channel: 'email', to, subject, body: html, event, userId, status });
  } catch (err) {
    await notificationRepo.record({ channel: 'email', to, subject, event, userId, status: 'failed', error: err.message });
    throw err;
  }
}

export async function sendSms({ to, body, event, userId }) {
  if (!to) return;
  // Plug a real provider (e.g. Africa's Talking / Twilio) here when SMS_API_KEY is configured.
  const status = env.sms.apiKey ? 'sent' : 'logged';
  if (!env.isTest) console.log(`📱 [sms:${event}] to=${to} "${body}"`);
  await notificationRepo.record({ channel: 'sms', to, body, event, userId, status });
}
