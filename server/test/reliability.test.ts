import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import { afterEach, describe, expect, it } from 'vitest';
import { mockGateways, MockNotifier, MockStoreOrderGateway, type Gateways } from '../src/adapters/gateways.js';
import { loadConfig } from '../src/config.js';
import { maskPhone } from '../src/modules/notifications.js';
import { closeTestContext, createTestContext, resetData, type TestContext } from './helpers.js';

const contexts: TestContext[] = [];
afterEach(async () => {
  while (contexts.length) await closeTestContext(contexts.pop()!);
});

async function context(options: Parameters<typeof createTestContext>[0] = {}) {
  const ctx = await createTestContext(options);
  await resetData(ctx.db);
  contexts.push(ctx);
  return ctx;
}

const never = <T>() => new Promise<T>(() => {});
const later = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();

async function rows(ctx: TestContext, sql: string, params: unknown[] = []) {
  const [result] = await ctx.db.query<RowDataPacket[]>(sql, params);
  return result;
}

describe('같은 요청 다시 보내기(Idempotency-Key)', () => {
  it('같은 키로 다시 주문하면 매장에 다시 요청하지 않고 처음 결과를 돌려준다', async () => {
    let calls = 0;
    const gateways = mockGateways();
    const orders = new MockStoreOrderGateway();
    gateways.storeOrders = { provider: 'mock', placeOrder: (r) => (calls++, orders.placeOrder(r)), cancelOrder: (r) => orders.cancelOrder(r) };
    const ctx = await context({ gateways });
    const payload = { storeId: 's-semo-yeoksam', item: '아메리카노', pickupAt: later(30) };
    const headers = { 'idempotency-key': 'errand-1-step-1' };
    const first = await ctx.app.inject({ method: 'POST', url: '/v1/orders', payload, headers });
    const second = await ctx.app.inject({ method: 'POST', url: '/v1/orders', payload, headers });
    expect(first.statusCode).toBe(201);
    expect(second.json()).toEqual(first.json());
    expect(calls).toBe(1);
    expect(await rows(ctx, 'SELECT status FROM orders')).toEqual([{ status: 'ACCEPTED' }]);
  });

  it('같은 키로 다른 내용을 보내면 422', async () => {
    const ctx = await context();
    const headers = { 'idempotency-key': 'k-1' };
    await ctx.app.inject({ method: 'POST', url: '/v1/orders', headers, payload: { storeId: 's-semo-yeoksam', item: '라테', pickupAt: later(30) } });
    const res = await ctx.app.inject({ method: 'POST', url: '/v1/orders', headers, payload: { storeId: 's-semo-yeoksam', item: '모카', pickupAt: later(30) } });
    expect(res.statusCode).toBe(422);
  });

  it('같은 키로 시간 변경을 다시 보내면 같은 변경 id를 돌려준다', async () => {
    const ctx = await context();
    const id = randomUUID();
    await ctx.app.inject({ method: 'PUT', url: `/v1/reservations/${id}`, payload: { placeName: 'x', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 } });
    const send = () =>
      ctx.app.inject({ method: 'POST', url: `/v1/reservations/${id}/reschedule`, headers: { 'idempotency-key': 'e1-1' }, payload: { reservedAt: '2026-11-05T10:30:00Z' } });
    const first = (await send()).json();
    const second = await send();
    expect(second.statusCode).toBe(200);
    expect(second.json().changeId).toBe(first.changeId);
    expect(await rows(ctx, 'SELECT status FROM reservation_changes')).toEqual([{ status: 'ACCEPTED' }]);
  });

  it('알림을 다시 보내도 이미 받은 사람에게는 다시 보내지 않는다', async () => {
    const sentTo: string[] = [];
    const gateways = mockGateways();
    const notifier = new MockNotifier();
    gateways.notifier = { provider: 'mock', send: (m) => (sentTo.push(m.name), notifier.send()) };
    const ctx = await context({ gateways });
    const minsu = randomUUID();
    await ctx.app.inject({ method: 'PUT', url: `/v1/contacts/${minsu}`, payload: { name: '김민수', phone: '010-2481-3307' } });
    const send = () => ctx.app.inject({ method: 'POST', url: '/v1/notifications', headers: { 'idempotency-key': 'e1-3' }, payload: { message: '늦어요', contactIds: [minsu] } });
    expect((await send()).json().results[0].status).toBe('SENT');
    expect((await send()).json().results[0].status).toBe('SENT');
    expect(sentTo).toEqual(['김민수']);
  });
});

describe('외부 시스템이 늦거나 실패할 때', () => {
  it('매장 응답이 없으면 504로 알리고 주문을 결과 미확인으로 남긴다. 다시 보내도 또 주문하지 않는다', async () => {
    let calls = 0;
    const gateways: Gateways = { ...mockGateways(), storeOrders: { provider: 'mock', placeOrder: () => (calls++, never()), cancelOrder: never } };
    const ctx = await context({ gateways, gatewayTimeoutMs: 50 });
    const payload = { storeId: 's-semo-yeoksam', item: '라테', pickupAt: later(30) };
    const send = () => ctx.app.inject({ method: 'POST', url: '/v1/orders', headers: { 'idempotency-key': 'slow-1' }, payload });
    const res = await send();
    expect(res.statusCode).toBe(504);
    expect(res.json().error.code).toBe('GATEWAY_TIMEOUT');
    expect(await rows(ctx, 'SELECT status FROM orders')).toEqual([{ status: 'UNKNOWN' }]);
    expect((await send()).statusCode).toBe(504);
    expect(calls).toBe(1);
  });

  it('매장이 거절한 주문도 기록을 남긴다', async () => {
    const ctx = await context();
    const res = await ctx.app.inject({ method: 'POST', url: '/v1/orders', payload: { storeId: 's-noeul-seocho', item: '식빵', pickupAt: later(1) } });
    expect(res.statusCode).toBe(409);
    expect(await rows(ctx, 'SELECT status, failure FROM orders')).toEqual([{ status: 'REJECTED', failure: '조리 시간보다 이른 픽업은 받을 수 없습니다' }]);
  });

  it('문자 공급자 오류는 정해진 코드로만 남기고 원문은 응답하지 않는다', async () => {
    const gateways: Gateways = { ...mockGateways(), notifier: { provider: 'sms', send: async () => { throw new Error('secret https://internal/api?key=abc'); } } };
    const ctx = await context({ gateways });
    const minsu = randomUUID();
    await ctx.app.inject({ method: 'PUT', url: `/v1/contacts/${minsu}`, payload: { name: '김민수', phone: '010-2481-3307' } });
    const res = await ctx.app.inject({ method: 'POST', url: '/v1/notifications', payload: { message: '늦어요', contactIds: [minsu] } });
    expect(res.json().results[0].status).toBe('FAILED');
    const history = (await ctx.app.inject({ method: 'GET', url: '/v1/notifications' })).json().notifications;
    expect(history[0]).toMatchObject({ status: 'FAILED', error: 'PROVIDER_ERROR', recipientPhone: '010-****-3307' });
  });

  it('식당을 기다리는 동안 예약이 바뀌면 덮어쓰지 않고 409', async () => {
    let ctx!: TestContext;
    const id = randomUUID();
    const base = mockGateways();
    const gateways: Gateways = {
      ...base,
      restaurants: {
        provider: 'mock',
        requestReschedule: async () => {
          await ctx.app.inject({ method: 'PUT', url: `/v1/reservations/${id}`, payload: { placeName: 'x', reservedAt: '2026-11-05T11:00:00Z', partySize: 2 } });
          return { accepted: true };
        },
        revertReschedule: (r) => base.restaurants.revertReschedule(r),
      },
    };
    ctx = await context({ gateways });
    await ctx.app.inject({ method: 'PUT', url: `/v1/reservations/${id}`, payload: { placeName: 'x', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 } });
    const res = await ctx.app.inject({ method: 'POST', url: `/v1/reservations/${id}/reschedule`, payload: { reservedAt: '2026-11-05T10:30:00Z' } });
    expect(res.json().error.code).toBe('CHANGED_SINCE');
    expect((await ctx.app.inject({ method: 'GET', url: '/v1/reservations' })).json().reservations[0].reservedAt).toBe('2026-11-05T11:00:00.000Z');
    expect(await rows(ctx, 'SELECT status FROM reservation_changes')).toEqual([{ status: 'CONFLICT' }]);
  });
});

describe('보호 장치', () => {
  it('알림 발송은 분당 한도를 넘으면 429', async () => {
    const ctx = await context({ notifyLimitPerMinute: 2 });
    const minsu = randomUUID();
    await ctx.app.inject({ method: 'PUT', url: `/v1/contacts/${minsu}`, payload: { name: '김민수', phone: '010-2481-3307' } });
    const send = () => ctx.app.inject({ method: 'POST', url: '/v1/notifications', payload: { message: 'x', contactIds: [minsu] } });
    await send();
    await send();
    const blocked = await send();
    expect(blocked.statusCode).toBe(429);
    expect(blocked.json().error.code).toBe('RATE_LIMITED');
  });

  it('밀리초가 붙은 시각은 초 단위로 맞춰 응답과 조회 값이 같다', async () => {
    const ctx = await context();
    const id = randomUUID();
    const res = await ctx.app.inject({ method: 'PUT', url: `/v1/reservations/${id}`, payload: { placeName: 'x', reservedAt: '2026-11-05T10:00:00.600Z', partySize: 2 } });
    expect(res.json().reservedAt).toBe('2026-11-05T10:00:00.000Z');
  });

  it('외부에 여는 주소에서는 API_TOKEN 없이 시작하지 않는다', () => {
    expect(() => loadConfig({ HOST: '0.0.0.0' })).toThrow(/API_TOKEN/);
    expect(loadConfig({ HOST: '0.0.0.0', API_TOKEN: 't'.repeat(20) }).HOST).toBe('0.0.0.0');
  });

  it('전화번호는 가운데를 가린다', () => {
    expect(maskPhone('010-2481-3307')).toBe('010-****-3307');
    expect(maskPhone('011-248-3307')).toBe('011-***-3307');
  });
});
