import { afterAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';

describe('GET /health', () => {
  const app = buildApp({ LOG_LEVEL: 'silent' });
  afterAll(() => app.close());

  it('200과 ok를 반환한다', async () => {
    const res = await app.inject({ method: 'GET', url: '/health' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: 'ok' });
  });
});
