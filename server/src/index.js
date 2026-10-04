import { createApp } from './app.js';
import { env } from './config/env.js';
import { migrate } from './db/migrate.js';
import { connectMongo, disconnectMongo } from './db/mongo.js';
import { pool } from './db/postgres.js';
import { closeBroker, connectBroker } from './messaging/broker.js';
import { startConsumers } from './workers/notificationWorker.js';

async function main() {
  await migrate();
  await connectMongo();
  const brokerUp = await connectBroker();
  // Without RabbitMQ the in-process bus needs local consumers; with it, a
  // separate worker can do the job unless RUN_WORKER_IN_PROCESS=true.
  if (!brokerUp || env.runWorkerInProcess) await startConsumers();

  const server = createApp().listen(env.port, () => {
    console.log(`🌸 Bloom & Co. API listening on ${env.serverUrl} (${env.nodeEnv})`);
  });

  const shutdown = async (signal) => {
    console.log(`${signal} received - shutting down`);
    server.close();
    await Promise.allSettled([closeBroker(), disconnectMongo(), pool.end()]);
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  console.error('Failed to start:', err);
  process.exit(1);
});
