import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { mockGateways } from '../src/adapters/gateways.js';
import { closeTestContext, createTestContext, resetData, type TestContext } from './helpers.js';

let ctx: TestContext;
beforeAll(async () => {
  ctx = await createTestContext();
});
afterAll(() => closeTestContext(ctx));
beforeEach(() => resetData(ctx.db));

type Body = Record<string, unknown>;
const put = (url: string, payload: Body) => ctx.app.inject({ method: 'PUT', url, payload });
const post = (url: string, payload: Body) => ctx.app.inject({ method: 'POST', url, payload });
const get = (url: string) => ctx.app.inject({ method: 'GET', url });

async function contact(name = '김민수', phone = '01024813307') {
  const id = randomUUID();
  const res = await put(`/v1/contacts/${id}`, { name, phone });
  expect(res.statusCode).toBe(200);
  return id;
}

describe('일행', () => {
  it('저장하면 번호를 하이픈 형식으로 맞추고 같은 id로 다시 보내면 갱신한다', async () => {
    const id = await contact();
    expect((await get('/v1/contacts')).json().contacts).toEqual([{ id, name: '김민수', phone: '010-2481-3307' }]);
    await put(`/v1/contacts/${id}`, { name: '김민수2', phone: '010-1111-2222' });
    expect((await get('/v1/contacts')).json().contacts[0].name).toBe('김민수2');
  });

  it('휴대폰 번호가 아니면 400', async () => {
    const res = await put(`/v1/contacts/${randomUUID()}`, { name: 'x', phone: '02-123-4567' });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('INVALID_REQUEST');
  });

  it('없는 일행 삭제는 404', async () => {
    expect((await ctx.app.inject({ method: 'DELETE', url: `/v1/contacts/${randomUUID()}` })).statusCode).toBe(404);
  });
});

describe('예약', () => {
  it('일행과 함께 저장하고 UTC ISO 시각으로 돌려준다', async () => {
    const minsu = await contact();
    const id = randomUUID();
    const res = await put(`/v1/reservations/${id}`, {
      placeName: '온담 한식당',
      reservedAt: '2026-11-05T19:00:00+09:00',
      partySize: 3,
      companionIds: [minsu],
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ id, placeName: '온담 한식당', reservedAt: '2026-11-05T10:00:00.000Z', partySize: 3, companionIds: [minsu] });
  });

  it('오프셋 없는 시각은 거부한다', async () => {
    const res = await put(`/v1/reservations/${randomUUID()}`, { placeName: 'x', reservedAt: '2026-11-05T19:00:00', partySize: 2 });
    expect(res.statusCode).toBe(400);
  });

  it('등록되지 않은 일행이 있으면 저장하지 않는다', async () => {
    const id = randomUUID();
    const res = await put(`/v1/reservations/${id}`, {
      placeName: 'x',
      reservedAt: '2026-11-05T10:00:00Z',
      partySize: 2,
      companionIds: [randomUUID()],
    });
    expect(res.statusCode).toBe(400);
    expect((await get('/v1/reservations')).json().reservations).toEqual([]);
  });

  it('일행을 지우면 예약의 알림 대상에서도 빠진다', async () => {
    const minsu = await contact();
    const id = randomUUID();
    await put(`/v1/reservations/${id}`, { placeName: 'x', reservedAt: '2026-11-05T10:00:00Z', partySize: 2, companionIds: [minsu] });
    await ctx.app.inject({ method: 'DELETE', url: `/v1/contacts/${minsu}` });
    expect((await get('/v1/reservations')).json().reservations[0].companionIds).toEqual([]);
  });

  it('시간 변경을 요청하면 예약 시각이 바뀌고 이력이 남는다', async () => {
    const id = randomUUID();
    await put(`/v1/reservations/${id}`, { placeName: '온담 한식당', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 });
    const res = await post(`/v1/reservations/${id}/reschedule`, { reservedAt: '2026-11-05T19:30:00+09:00' });
    expect(res.statusCode).toBe(200);
    expect(res.json().reservedAt).toBe('2026-11-05T10:30:00.000Z');
    const [rows] = await ctx.db.query('SELECT status FROM reservation_changes WHERE reservation_id = ?', [id]);
    expect(rows).toEqual([{ status: 'ACCEPTED' }]);
  });

  it('식당이 거절하면 409이고 예약은 그대로다', async () => {
    const id = randomUUID();
    await put(`/v1/reservations/${id}`, { placeName: 'x', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 });
    const res = await post(`/v1/reservations/${id}/reschedule`, { reservedAt: '2026-11-05T09:30:00Z' });
    expect(res.statusCode).toBe(409);
    expect((await get('/v1/reservations')).json().reservations[0].reservedAt).toBe('2026-11-05T10:00:00.000Z');
    const [rows] = await ctx.db.query('SELECT status FROM reservation_changes WHERE reservation_id = ?', [id]);
    expect(rows).toEqual([{ status: 'REJECTED' }]);
  });
  it('받아들여진 변경을 되돌리면 원래 시각으로 돌아가고 두 번은 못 되돌린다', async () => {
    const id = randomUUID();
    await put(`/v1/reservations/${id}`, { placeName: '온담 한식당', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 });
    const { changeId } = (await post(`/v1/reservations/${id}/reschedule`, { reservedAt: '2026-11-05T10:30:00Z' })).json();
    expect(changeId).toEqual(expect.any(String));
    const reverted = await post(`/v1/reservation-changes/${changeId}/revert`, {});
    expect(reverted.statusCode).toBe(200);
    expect(reverted.json().reservedAt).toBe('2026-11-05T10:00:00.000Z');
    const [rows] = await ctx.db.query('SELECT status FROM reservation_changes WHERE id = ?', [changeId]);
    expect(rows).toEqual([{ status: 'REVERTED' }]);
    expect((await post(`/v1/reservation-changes/${changeId}/revert`, {})).json().error.code).toBe('NOT_REVERTIBLE');
  });

  it('그 뒤로 예약이 또 바뀌었으면 앞선 변경은 되돌리지 않는다', async () => {
    const id = randomUUID();
    await put(`/v1/reservations/${id}`, { placeName: 'x', reservedAt: '2026-11-05T10:00:00Z', partySize: 2 });
    const first = (await post(`/v1/reservations/${id}/reschedule`, { reservedAt: '2026-11-05T10:30:00Z' })).json().changeId;
    await post(`/v1/reservations/${id}/reschedule`, { reservedAt: '2026-11-05T10:50:00Z' });
    const res = await post(`/v1/reservation-changes/${first}/revert`, {});
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('CHANGED_SINCE');
    expect((await get('/v1/reservations')).json().reservations[0].reservedAt).toBe('2026-11-05T10:50:00.000Z');
  });
});

describe('매장·주문', () => {
  it('매장은 우회가 짧은 순서이고 이름으로 거른다', async () => {
    const all = (await get('/v1/stores')).json().stores;
    expect(all.map((s: { id: string }) => s.id)).toEqual(['s-semo-yeoksam', 's-hansot-gangnam', 's-noeul-seocho']);
    const filtered = (await get(`/v1/stores?name=${encodeURIComponent('노을베이커리')}`)).json().stores;
    expect(filtered.map((s: { id: string }) => s.id)).toEqual(['s-noeul-seocho']);
  });

  it('조리 시간 이후 픽업 주문을 받는다', async () => {
    const pickupAt = new Date(Date.now() + 30 * 60_000).toISOString();
    const res = await post('/v1/orders', { storeId: 's-semo-yeoksam', item: '아메리카노', pickupAt });
    expect(res.statusCode).toBe(201);
    expect(res.json()).toMatchObject({ storeId: 's-semo-yeoksam', item: '아메리카노', status: 'ACCEPTED' });
  });

  it('조리 시간보다 이른 픽업은 409', async () => {
    const pickupAt = new Date(Date.now() + 60_000).toISOString();
    expect((await post('/v1/orders', { storeId: 's-noeul-seocho', item: '식빵', pickupAt })).statusCode).toBe(409);
  });

  it('없는 매장은 404', async () => {
    expect((await post('/v1/orders', { storeId: 'nope', item: 'x', pickupAt: new Date().toISOString() })).statusCode).toBe(404);
  });
  it('주문 이력은 최근 픽업 순이고 매장 이름을 함께 준다', async () => {
    const soon = new Date(Date.now() + 20 * 60_000).toISOString();
    const later = new Date(Date.now() + 40 * 60_000).toISOString();
    await post('/v1/orders', { storeId: 's-semo-yeoksam', item: '아메리카노', pickupAt: soon });
    await post('/v1/orders', { storeId: 's-hansot-gangnam', item: '도시락', pickupAt: later });
    const orders = (await get('/v1/orders')).json().orders;
    expect(orders.map((o: { item: string }) => o.item)).toEqual(['도시락', '아메리카노']);
    expect(orders[1]).toMatchObject({ storeId: 's-semo-yeoksam', storeName: '세모 커피 역삼점', status: 'ACCEPTED' });
  });

  it('조리 전 주문은 취소하고 같은 주문을 다시 취소하면 409', async () => {
    const pickupAt = new Date(Date.now() + 30 * 60_000).toISOString();
    const { id } = (await post('/v1/orders', { storeId: 's-semo-yeoksam', item: '아메리카노', pickupAt })).json();
    const res = await post(`/v1/orders/${id}/cancel`, {});
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe('CANCELLED');
    expect((await post(`/v1/orders/${id}/cancel`, {})).json().error.code).toBe('NOT_CANCELLABLE');
  });

  it('매장이 조리를 시작한 뒤에는 취소를 거절한다', async () => {
    let clock = Date.now();
    const late = await createTestContext({ gateways: mockGateways(() => new Date(clock)) });
    try {
      const pickupAt = new Date(clock + 30 * 60_000).toISOString();
      const { id } = (await late.app.inject({ method: 'POST', url: '/v1/orders', payload: { storeId: 's-semo-yeoksam', item: '라테', pickupAt } })).json();
      clock += 6 * 60_000;
      const res = await late.app.inject({ method: 'POST', url: `/v1/orders/${id}/cancel`, payload: {} });
      expect(res.statusCode).toBe(409);
      expect(res.json().error.code).toBe('CANCEL_REJECTED');
    } finally {
      await closeTestContext(late);
    }
  });
});

describe('알림', () => {
  it('일행마다 결과를 돌려주고 내역을 남긴다', async () => {
    const minsu = await contact();
    const seoyeon = await contact('이서연', '010-7716-0452');
    const res = await post('/v1/notifications', { message: '도착이 20분 늦어요', contactIds: [minsu, seoyeon] });
    expect(res.statusCode).toBe(200);
    expect(res.json().results.map((r: { status: string }) => r.status)).toEqual(['SENT', 'SENT']);
    const history = (await get('/v1/notifications')).json().notifications;
    expect(history).toHaveLength(2);
    expect(history[0]).toMatchObject({ provider: 'mock', status: 'SENT' });
  });

  it('등록되지 않은 일행이 섞이면 보내지 않는다', async () => {
    const minsu = await contact();
    expect((await post('/v1/notifications', { message: 'x', contactIds: [minsu, randomUUID()] })).statusCode).toBe(400);
    expect((await get('/v1/notifications')).json().notifications).toEqual([]);
  });
});

describe('충전·줄서기', () => {
  it('충전소는 우회가 짧은 순서이고 걸어갈 수 있는 식당을 함께 준다', async () => {
    const chargers = (await get('/v1/chargers')).json().chargers;
    expect(chargers.map((c: { id: string }) => c.id)).toEqual(['ch-ieum-yangjae', 'ch-green-seocho']);
    expect(chargers[0]).toMatchObject({ powerKw: 350, latitude: 37.4846 });
    expect(chargers[0].restaurants.map((r: { id: string }) => r.id)).toEqual(['cr-sobok-yangjae', 'cr-mulgyeol-yangjae']);
  });

  it('줄서기를 등록하면 앞 팀 수와 대기 시간을 돌려주고 취소할 수 있다', async () => {
    const res = await post('/v1/waitlists', { restaurantId: 'cr-sobok-yangjae', partySize: 2 });
    expect(res.statusCode).toBe(201);
    expect(res.json()).toMatchObject({ restaurantId: 'cr-sobok-yangjae', partySize: 2, position: 3, waitMinutes: 15, status: 'WAITING' });
    const cancelled = await post(`/v1/waitlists/${res.json().id}/cancel`, {});
    expect(cancelled.json().status).toBe('CANCELLED');
    expect((await post(`/v1/waitlists/${res.json().id}/cancel`, {})).statusCode).toBe(409);
  });

  it('없는 식당은 404', async () => {
    expect((await post('/v1/waitlists', { restaurantId: 'nope', partySize: 2 })).statusCode).toBe(404);
  });
});

describe('인증', () => {
  it('API_TOKEN이 있으면 Bearer 토큰 없이 401', async () => {
    const secured = await createTestContext({ apiToken: 's'.repeat(24) });
    try {
      expect((await secured.app.inject({ method: 'GET', url: '/v1/stores' })).statusCode).toBe(401);
      const ok = await secured.app.inject({ method: 'GET', url: '/v1/stores', headers: { authorization: `Bearer ${'s'.repeat(24)}` } });
      expect(ok.statusCode).toBe(200);
      expect((await secured.app.inject({ method: 'GET', url: '/health' })).statusCode).toBe(200);
    } finally {
      await secured.app.close();
      await secured.db.end();
    }
  });
});
