import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';
import { env } from '../config/env.js';

/**
 * RabbitMQ integration (topic exchange).
 *
 *  API ──publish(routingKey)──▶ [flowershop.events] ──▶ notifications.email ─▶ email worker
 *                                                   ├─▶ notifications.sms   ─▶ sms worker
 *                                                   └─▶ audit.events        ─▶ audit logger (MongoDB)
 *
 * When RabbitMQ is not reachable the same routing rules are applied by a
 * tiny in-process bus, so features keep working in local development.
 */
export const EXCHANGE = 'flowershop.events';

export const QUEUES = {
  email: {
    name: 'notifications.email',
    bindings: [
      'user.registered',
      'auth.password_reset_requested',
      'order.placed',
      'order.status_changed',
      'payment.completed',
      'contact.received',
      'contact.replied',
      'staff.created',
    ],
  },
  sms: {
    name: 'notifications.sms',
    bindings: ['order.placed', 'order.status_changed', 'delivery.assigned'],
  },
  audit: { name: 'audit.events', bindings: ['#'] },
};

let connection = null;
let channel = null;
const localConsumers = []; // { bindings, handler }

export const brokerMode = () => (channel ? 'rabbitmq' : 'in-process');

const matches = (bindings, key) =>
  bindings.some((b) => b === '#' || b === key || (b.endsWith('.*') && key.startsWith(b.slice(0, -1))));

export async function connectBroker({ log = console.log } = {}) {
  if (!env.rabbitUrl) {
    log('⚠ RABBITMQ_URL not set - using in-process event bus');
    return false;
  }
  try {
    connection = await amqp.connect(env.rabbitUrl);
    channel = await connection.createChannel();
    await channel.assertExchange(EXCHANGE, 'topic', { durable: true });
    // dead-letter exchange so failed messages are kept for inspection
    await channel.assertExchange(`${EXCHANGE}.dlx`, 'fanout', { durable: true });
    await channel.assertQueue('events.dead-letter', { durable: true });
    await channel.bindQueue('events.dead-letter', `${EXCHANGE}.dlx`, '');
    for (const q of Object.values(QUEUES)) {
      await channel.assertQueue(q.name, {
        durable: true,
        arguments: { 'x-dead-letter-exchange': `${EXCHANGE}.dlx` },
      });
      for (const key of q.bindings) await channel.bindQueue(q.name, EXCHANGE, key);
    }
    await channel.prefetch(10);
    connection.on('close', () => {
      log('⚠ RabbitMQ connection closed - switching to in-process bus');
      channel = null;
      connection = null;
    });
    connection.on('error', () => {});
    log('✔ RabbitMQ connected');
    return true;
  } catch (err) {
    log(`⚠ RabbitMQ unavailable (${err.message}) - using in-process event bus`);
    channel = null;
    return false;
  }
}

/** Publish a domain event. Never throws: messaging must not break a checkout. */
export function publish(routingKey, data) {
  const message = { id: randomUUID(), event: routingKey, occurredAt: new Date().toISOString(), data };
  try {
    if (channel) {
      channel.publish(EXCHANGE, routingKey, Buffer.from(JSON.stringify(message)), {
        persistent: true,
        contentType: 'application/json',
        messageId: message.id,
        type: routingKey,
      });
      return message;
    }
  } catch (err) {
    console.error('publish failed, falling back to local bus:', err.message);
  }
  for (const c of localConsumers) {
    if (matches(c.bindings, routingKey)) {
      setImmediate(() => Promise.resolve(c.handler(message)).catch((e) => console.error(e.message)));
    }
  }
  return message;
}

/** Register a consumer for one of the QUEUES. */
export async function consume(queueKey, handler) {
  const q = QUEUES[queueKey];
  localConsumers.push({ bindings: q.bindings, handler });
  if (!channel) return;
  await channel.consume(q.name, async (msg) => {
    if (!msg) return;
    try {
      await handler(JSON.parse(msg.content.toString()));
      channel?.ack(msg);
    } catch (err) {
      console.error(`[${q.name}] handler failed:`, err.message);
      channel?.nack(msg, false, false); // -> dead-letter queue
    }
  });
}

export async function closeBroker() {
  try {
    await channel?.close();
    await connection?.close();
  } catch {
    /* ignore */
  }
  channel = null;
  connection = null;
}
