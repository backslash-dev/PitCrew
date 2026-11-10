import { mockGateways } from './adapters/gateways.js';
import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { migrate } from './db/migrate.js';
import { createPool } from './db/pool.js';

const config = loadConfig();
await migrate(config.DATABASE_URL, (message) => console.log(message));
const db = createPool(config.DATABASE_URL);
const app = buildApp(config, {
  db,
  gateways: mockGateways(),
  gatewayTimeoutMs: config.GATEWAY_TIMEOUT_MS,
  notifyLimitPerMinute: config.NOTIFY_LIMIT_PER_MINUTE,
});

const SHUTDOWN_TIMEOUT_MS = 15_000;
let shuttingDown = false;
async function shutdown(signal: NodeJS.Signals) {
  if (shuttingDown) return;
  shuttingDown = true;
  app.log.info({ signal }, 'shutting down');
  // 외부 호출을 기다리는 요청 때문에 종료가 끝나지 않으면 강제로 끝낸다
  setTimeout(() => {
    app.log.error('shutdown timed out, forcing exit');
    process.exit(1);
  }, SHUTDOWN_TIMEOUT_MS).unref();
  try {
    await app.close();
    await db.end();
    process.exit(0);
  } catch (err) {
    app.log.error(err, 'shutdown failed');
    process.exit(1);
  }
}

process.on('unhandledRejection', (reason) => app.log.error({ err: reason }, 'unhandled rejection'));
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

try {
  await app.listen({ host: config.HOST, port: config.PORT });
} catch (err) {
  app.log.error(err, 'failed to start');
  process.exit(1);
}
