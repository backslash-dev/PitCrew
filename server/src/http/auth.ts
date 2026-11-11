import { timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { HttpError } from './errors.js';

export function registerAuth(app: FastifyInstance, token: string | undefined) {
  if (!token) return;
  const expected = Buffer.from(token);
  app.addHook('onRequest', async (request) => {
    const header = request.headers.authorization ?? '';
    const given = Buffer.from(header.startsWith('Bearer ') ? header.slice(7) : '');
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) {
      throw new HttpError(401, 'UNAUTHORIZED', '인증이 필요합니다');
    }
  });
}
