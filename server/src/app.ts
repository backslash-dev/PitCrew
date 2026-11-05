import Fastify, { type FastifyInstance } from 'fastify';
import type { Config } from './config.js';
import { registerAuth } from './http/auth.js';
import { registerErrorHandler } from './http/errors.js';
import { chargingRoutes } from './modules/charging.js';
import { contactRoutes } from './modules/contacts.js';
import type { Deps } from './modules/deps.js';
import { notificationRoutes } from './modules/notifications.js';
import { orderRoutes } from './modules/orders.js';
import { reservationRoutes } from './modules/reservations.js';
import { storeRoutes } from './modules/stores.js';
import { healthRoutes } from './routes/health.js';

export function buildApp(config: Pick<Config, 'LOG_LEVEL' | 'API_TOKEN'>, deps: Deps): FastifyInstance {
  const app = Fastify({ logger: { level: config.LOG_LEVEL } });
  registerErrorHandler(app);
  app.register(healthRoutes, { db: deps.db });
  app.register(
    async (v1) => {
      registerAuth(v1, config.API_TOKEN);
      await v1.register(contactRoutes, deps);
      await v1.register(reservationRoutes, deps);
      await v1.register(storeRoutes, deps);
      await v1.register(orderRoutes, deps);
      await v1.register(notificationRoutes, deps);
      await v1.register(chargingRoutes, deps);
    },
    { prefix: '/v1' },
  );
  return app;
}
