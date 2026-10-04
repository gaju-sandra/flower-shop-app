import { fileURLToPath } from 'node:url';
import { connectBroker, consume, brokerMode } from '../messaging/broker.js';
import { handleAudit, handleEmail, handleSms } from '../messaging/handlers.js';
import { connectMongo } from '../db/mongo.js';

/** Subscribes the notification + audit handlers to their queues. */
export async function startConsumers() {
  await consume('email', handleEmail);
  await consume('sms', handleSms);
  await consume('audit', handleAudit);
}

// Stand-alone worker process: `npm run worker`
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  (async () => {
    await connectMongo();
    const ok = await connectBroker();
    if (!ok) {
      console.error('Worker requires RabbitMQ (RABBITMQ_URL). Exiting.');
      process.exit(1);
    }
    await startConsumers();
    console.log(`📬 notification worker listening (${brokerMode()})`);
  })();
}
