import pg from 'pg';
import { env } from '../config/env.js';

// NUMERIC columns come back as strings by default; money values here fit safely in a JS number.
pg.types.setTypeParser(1700, (v) => (v === null ? null : Number(v)));
// BIGINT (COUNT(*)) -> number
pg.types.setTypeParser(20, (v) => (v === null ? null : Number(v)));
// DATE -> keep as 'YYYY-MM-DD' string (avoid timezone shifts)
pg.types.setTypeParser(1082, (v) => v);

export const pool = new pg.Pool({
  connectionString: env.databaseUrl,
  max: 15,
  idleTimeoutMillis: 30_000,
});

export const query = (text, params) => pool.query(text, params);

/** Run `fn(client)` inside a transaction; rolls back on any thrown error. */
export async function withTransaction(fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}
