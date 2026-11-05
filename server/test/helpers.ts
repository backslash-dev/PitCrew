import type { FastifyInstance } from 'fastify';
import type { Pool } from 'mysql2/promise';
import { mockGateways, type Gateways } from '../src/adapters/gateways.js';
import { buildApp } from '../src/app.js';
import { migrate } from '../src/db/migrate.js';
import { createPool } from '../src/db/pool.js';

export const TEST_DATABASE_URL = process.env.TEST_DATABASE_URL ?? 'mysql://pitcrew:pitcrew@127.0.0.1:3308/pitcrew_test';

const DATA_TABLES = ['notifications', 'orders', 'reservation_changes', 'reservation_companions', 'reservations', 'contacts'];

export interface TestContext {
  app: FastifyInstance;
  db: Pool;
}

export async function createTestContext(options: { apiToken?: string; gateways?: Gateways } = {}): Promise<TestContext> {
  await migrate(TEST_DATABASE_URL);
  const db = createPool(TEST_DATABASE_URL);
  const config = options.apiToken ? { LOG_LEVEL: 'silent' as const, API_TOKEN: options.apiToken } : { LOG_LEVEL: 'silent' as const };
  const app = buildApp(config, { db, gateways: options.gateways ?? mockGateways() });
  await app.ready();
  return { app, db };
}

export async function resetData(db: Pool) {
  for (const table of DATA_TABLES) await db.query(`DELETE FROM ${table}`);
}

export async function closeTestContext({ app, db }: TestContext) {
  await app.close();
  await db.end();
}
