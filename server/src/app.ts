import rateLimit from '@fastify/rate-limit';
import Fastify, { type FastifyInstance } from 'fastify';
import type { Config } from './config.js';
import { registerAuth } from './http/auth.js';
import { HttpError, registerErrorHandler } from './http/errors.js';
import { chargingRoutes } from './modules/charging.js';
import { contactRoutes } from './modules/contacts.js';
import { mailRoutes } from './modules/mails.js';
import { meetupRoutes } from './modules/meetups.js';
import type { Deps } from './modules/deps.js';
import { notificationRoutes } from './modules/notifications.js';
import { orderRoutes } from './modules/orders.js';
import { reservationRoutes } from './modules/reservations.js';
import { storeRoutes } from './modules/stores.js';
import { healthRoutes } from './routes/health.js';

type AppConfig = Pick<Config, 'LOG_LEVEL' | 'API_TOKEN'> & Partial<Pick<Config, 'RATE_LIMIT_PER_MINUTE' | 'TRUST_PROXY'>>;

export function buildApp(config: AppConfig, deps: Deps): FastifyInstance {
  // 프록시 뒤에서는 X-Forwarded-For를 믿어야 요청 제한이 사람별로 걸린다
  const app = Fastify({ logger: { level: config.LOG_LEVEL }, trustProxy: config.TRUST_PROXY ?? false });
  registerErrorHandler(app);
  app.register(healthRoutes, { db: deps.db });
  app.register(
    async (v1) => {
      registerAuth(v1, config.API_TOKEN);
      await v1.register(rateLimit, {
        max: config.RATE_LIMIT_PER_MINUTE ?? 120,
        timeWindow: '1 minute',
        // 토큰을 쓰는 기기는 토큰 단위, 아니면 IP 단위로 센다
        keyGenerator: (request) => request.headers.authorization ?? request.ip,
        errorResponseBuilder: () => new HttpError(429, 'RATE_LIMITED', '요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요'),
      });
      await v1.register(contactRoutes, deps);
      await v1.register(reservationRoutes, deps);
      await v1.register(storeRoutes, deps);
      await v1.register(orderRoutes, deps);
      await v1.register(notificationRoutes, deps);
      await v1.register(chargingRoutes, deps);
      await v1.register(meetupRoutes, deps);
      await v1.register(mailRoutes, deps);
    },
    { prefix: '/v1' },
  );
  return app;
}
