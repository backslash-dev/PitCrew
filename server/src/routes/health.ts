import type { FastifyPluginAsync } from 'fastify';
import type { Pool } from 'mysql2/promise';

export const healthRoutes: FastifyPluginAsync<{ db?: Pool }> = async (app, { db }) => {
  app.get('/health', async (_request, reply) => {
    if (db) {
      try {
        await db.query('SELECT 1');
      } catch {
        return reply.status(503).send({ status: 'degraded', database: 'unavailable' });
      }
    }
    return { status: 'ok' };
  });
};
