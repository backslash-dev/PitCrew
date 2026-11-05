import Fastify, { type FastifyInstance } from 'fastify';
import type { Config } from './config.js';
import { healthRoutes } from './routes/health.js';

export function buildApp(config: Pick<Config, 'LOG_LEVEL'>): FastifyInstance {
  const app = Fastify({ logger: { level: config.LOG_LEVEL } });
  app.register(healthRoutes);
  return app;
}
