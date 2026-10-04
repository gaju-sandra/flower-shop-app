import mongoose from 'mongoose';
import { env } from '../config/env.js';

let memoryServer = null;
let ready = false;

export const isMongoReady = () => ready;

/**
 * Connects to MongoDB. MONGO_URI=memory starts an embedded server (dev only).
 * If MongoDB is unreachable the app keeps running and the document repositories
 * fall back to an in-memory store, so the shop never goes down because of it.
 */
export async function connectMongo({ log = console.log } = {}) {
  let uri = env.mongoUri;
  if (!uri) {
    log('⚠ MONGO_URI not set - document store running in memory-only fallback mode');
    return false;
  }
  try {
    if (uri === 'memory') {
      const { MongoMemoryServer } = await import('mongodb-memory-server');
      memoryServer = await MongoMemoryServer.create();
      uri = memoryServer.getUri('flower_shop');
      log('ℹ started embedded MongoDB (development)');
    }
    mongoose.set('bufferCommands', false);
    await mongoose.connect(uri, { serverSelectionTimeoutMS: 4000 });
    ready = true;
    log('✔ MongoDB connected');
    return true;
  } catch (err) {
    log(`⚠ MongoDB unavailable (${err.message}) - using in-memory fallback`);
    return false;
  }
}

export async function disconnectMongo() {
  if (ready) await mongoose.disconnect();
  if (memoryServer) await memoryServer.stop();
  ready = false;
}
