import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import { afterEach, describe, expect, it } from 'vitest';
import { mockGateways, type Gateways, type PeerAgentGateway } from '../src/adapters/gateways.js';
import { meetTimeText } from '../src/modules/meetups.js';
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
const TEN_MINUTES = 10 * 60_000;

async function rows(ctx: TestContext, sql: string, params: unknown[] = []) {
  const [result] = await ctx.db.query<RowDataPacket[]>(sql, params);
  return result;
}

async function peers(ctx: TestContext) {
  const minsu = randomUUID();
  const seoyeon = randomUUID();
  await ctx.app.inject({ method: 'PUT', url: `/v1/contacts/${minsu}`, payload: { name: '김민수', phone: '010-2481-3307' } });
  await ctx.app.inject({ method: 'PUT', url: `/v1/contacts/${seoyeon}`, payload: { name: '이서연', phone: '010-7716-0452' } });
  const from = new Date(Date.now() - 60_000);
  const until = new Date(Date.now() + 6 * 3_600_000);
  await ctx.db.query('INSERT INTO peer_profiles (contact_id, has_agent, latitude, longitude, free_from, free_until) VALUES ?', [
    [
      [minsu, 1, 37.3948, 127.1112, from, until],
      [seoyeon, 0, 37.5563, 126.9236, from, until],
    ],
  ]);
  return { minsu, seoyeon };
}

function recordingGateways() {
  const sms: string[] = [];
  const agent: string[] = [];
  const base = mockGateways();
  const gateways: Gateways = {
    ...base,
    notifier: { provider: 'mock', send: (m) => (sms.push(m.text), base.notifier.send(m)) },
    peerAgents: {
      provider: 'mock',
      confirm: (r) => (agent.push(`confirm ${r.placeName}`), base.peerAgents.confirm(r)),
      cancel: (r) => (agent.push(`cancel ${r.placeName}`), base.peerAgents.cancel(r)),
    },
  };
  return { gateways, sms, agent };
}

const post = (ctx: TestContext, url: string, payload: Record<string, unknown>, key?: string) =>
  ctx.app.inject({ method: 'POST', url, payload, ...(key ? { headers: { 'idempotency-key': key } } : {}) });

describe('약속 계획', () => {
  it('에이전트가 있는 일행이면 둘 다 비슷하게 걸리는 곳과 시각을 바로 돌려주고 아무것도 남기지 않는다', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    const before = Date.now();
    const res = await post(ctx, '/v1/meetups/plan', { contactId: minsu });
    expect(res.statusCode).toBe(200);
    const plan = res.json();
    expect(plan).toMatchObject({ mode: 'AGENT', contact: { id: minsu, name: '김민수' }, place: { id: 'mp-suseo', name: '정원 카페 수서', latitude: 37.4873 } });
    expect(plan.userTravelMinutes).toEqual(expect.any(Number));
    const meetAt = Date.parse(plan.meetAt);
    expect(meetAt % TEN_MINUTES).toBe(0);
    expect(meetAt).toBeGreaterThanOrEqual(before + Math.max(plan.userTravelMinutes, plan.peerTravelMinutes) * 60_000);
    expect(await rows(ctx, 'SELECT id FROM meetups')).toEqual([]);
    expect(await rows(ctx, 'SELECT id FROM notifications')).toEqual([]);
  });

  it('출발 위치를 주면 그 위치로 계산한다', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    const res = await post(ctx, '/v1/meetups/plan', { contactId: minsu, latitude: 37.3948, longitude: 127.1112 });
    expect(res.json().place.id).toBe('mp-pangyo');
    expect((await post(ctx, '/v1/meetups/plan', { contactId: minsu, latitude: 37.39 })).statusCode).toBe(400);
  });

  it('에이전트가 없는 일행이면 문자로 물어봐야 한다고만 알려 준다', async () => {
    const ctx = await context();
    const { seoyeon } = await peers(ctx);
    const res = await post(ctx, '/v1/meetups/plan', { contactId: seoyeon });
    expect(res.json()).toEqual({ mode: 'SMS', contact: { id: seoyeon, name: '이서연' } });
    expect(await rows(ctx, 'SELECT id FROM notifications')).toEqual([]);
  });

  it('없는 일행은 404, 일행이 안 되는 시간이면 409 PEER_UNAVAILABLE', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    expect((await post(ctx, '/v1/meetups/plan', { contactId: randomUUID() })).statusCode).toBe(404);
    const late = await post(ctx, '/v1/meetups/plan', { contactId: minsu, preferredAt: new Date(Date.now() + 8 * 3_600_000).toISOString() });
    expect(late.statusCode).toBe(409);
    expect(late.json().error).toEqual({ code: 'PEER_UNAVAILABLE', message: '김민수님이 그 시간에는 어렵다고 해요' });
  });

  it('다른 예약과 90분 안으로 겹치면 미룬다', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    const first = Date.parse((await post(ctx, '/v1/meetups/plan', { contactId: minsu })).json().meetAt);
    await ctx.app.inject({
      method: 'PUT',
      url: `/v1/reservations/${randomUUID()}`,
      payload: { placeName: '온담 한식당', reservedAt: new Date(first).toISOString(), partySize: 2 },
    });
    const pushed = Date.parse((await post(ctx, '/v1/meetups/plan', { contactId: minsu })).json().meetAt);
    expect(pushed - first).toBeGreaterThanOrEqual(90 * 60_000);
  });

  it('후보 장소가 없으면 409 NO_PLACE', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    await ctx.db.query('UPDATE meetup_places SET active = 0');
    try {
      const res = await post(ctx, '/v1/meetups/plan', { contactId: minsu });
      expect(res.statusCode).toBe(409);
      expect(res.json().error.code).toBe('NO_PLACE');
    } finally {
      await ctx.db.query('UPDATE meetup_places SET active = 1');
    }
  });
});

describe('문자로 물어보기', () => {
  it('위치와 시간을 묻는 문자를 보내고 답장으로 계획을 만든다. 같은 키로 다시 보내도 문자는 한 번', async () => {
    const { gateways, sms } = recordingGateways();
    const ctx = await context({ gateways });
    const { seoyeon } = await peers(ctx);
    const first = await post(ctx, '/v1/meetups/ask', { contactId: seoyeon }, 'meet-1-ask');
    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({ mode: 'SMS', contact: { id: seoyeon, name: '이서연' }, place: { id: expect.any(String) } });
    expect(first.json().meetAt).toEqual(expect.any(String));
    const again = await post(ctx, '/v1/meetups/ask', { contactId: seoyeon }, 'meet-1-ask');
    expect(again.json()).toEqual(first.json());
    expect(sms).toHaveLength(1);
    expect(sms[0]).toMatch(/^PitCrew 알림 · /);
    expect(await rows(ctx, 'SELECT status FROM meetup_requests')).toEqual([{ status: 'REPLIED' }]);
    expect(await rows(ctx, 'SELECT status, contact_id FROM notifications')).toEqual([{ status: 'SENT', contact_id: seoyeon }]);
  });

  it('에이전트가 있는 일행이면 문자 없이 에이전트 계획을 준다', async () => {
    const { gateways, sms } = recordingGateways();
    const ctx = await context({ gateways });
    const { minsu } = await peers(ctx);
    expect((await post(ctx, '/v1/meetups/ask', { contactId: minsu })).json().mode).toBe('AGENT');
    expect(sms).toEqual([]);
  });

  it('답장이 없으면 504 NO_REPLY', async () => {
    const gateways: Gateways = { ...mockGateways(), peerReplies: { provider: 'mock', awaitReply: async () => ({ replied: false, reason: '응답 없음' }) } };
    const ctx = await context({ gateways });
    const { seoyeon } = await peers(ctx);
    const res = await post(ctx, '/v1/meetups/ask', { contactId: seoyeon });
    expect(res.statusCode).toBe(504);
    expect(res.json().error).toEqual({ code: 'NO_REPLY', message: '아직 답장이 없어요' });
    expect(await rows(ctx, 'SELECT status FROM meetup_requests')).toEqual([{ status: 'NO_REPLY' }]);
  });

  it('답장을 기다리다 시간이 지나도 NO_REPLY', async () => {
    const gateways: Gateways = { ...mockGateways(), peerReplies: { provider: 'mock', awaitReply: never } };
    const ctx = await context({ gateways, gatewayTimeoutMs: 50 });
    const { seoyeon } = await peers(ctx);
    expect((await post(ctx, '/v1/meetups/ask', { contactId: seoyeon })).json().error.code).toBe('NO_REPLY');
  });

  it('문자 공급자 오류는 502이고 묻기 기록은 결과 미확인으로 남는다', async () => {
    const gateways: Gateways = { ...mockGateways(), notifier: { provider: 'sms', send: async () => { throw new Error('down'); } } };
    const ctx = await context({ gateways });
    const { seoyeon } = await peers(ctx);
    const res = await post(ctx, '/v1/meetups/ask', { contactId: seoyeon });
    expect(res.statusCode).toBe(502);
    expect(res.json().error.code).toBe('GATEWAY_ERROR');
    expect(await rows(ctx, 'SELECT status FROM meetup_requests')).toEqual([{ status: 'UNKNOWN' }]);
    expect(await rows(ctx, 'SELECT status, error FROM notifications')).toEqual([{ status: 'FAILED', error: 'PROVIDER_ERROR' }]);
  });
});

describe('약속 잡기·알리기·취소', () => {
  async function book(ctx: TestContext, contactId: string, mode: 'AGENT' | 'SMS', key?: string) {
    const plan = (await post(ctx, mode === 'AGENT' ? '/v1/meetups/plan' : '/v1/meetups/ask', { contactId })).json();
    return post(ctx, '/v1/meetups', { contactId, placeId: plan.place.id, meetAt: plan.meetAt, mode }, key);
  }

  it('에이전트 약속: 예약으로 잡히고, 에이전트에 한 번만 알리고, 취소하면 예약이 사라진다', async () => {
    const { gateways, agent, sms } = recordingGateways();
    const ctx = await context({ gateways });
    const { minsu } = await peers(ctx);
    const booked = await book(ctx, minsu, 'AGENT');
    expect(booked.statusCode).toBe(201);
    const meetup = booked.json();
    expect(meetup).toEqual({
      id: expect.any(String),
      reservationId: expect.any(String),
      placeId: 'mp-suseo',
      placeName: '정원 카페 수서',
      meetAt: expect.stringMatching(/:[0-5]0:00\.000Z$/),
      mode: 'AGENT',
      status: 'BOOKED',
    });
    const reservations = (await ctx.app.inject({ method: 'GET', url: '/v1/reservations' })).json().reservations;
    expect(reservations).toEqual([{ id: meetup.reservationId, placeName: '정원 카페 수서', reservedAt: meetup.meetAt, partySize: 2, companionIds: [minsu] }]);

    expect((await post(ctx, `/v1/meetups/${meetup.id}/share`, {})).json()).toEqual({ id: meetup.id, status: 'SHARED' });
    expect((await post(ctx, `/v1/meetups/${meetup.id}/share`, {})).json()).toEqual({ id: meetup.id, status: 'SHARED' });
    expect(agent).toEqual(['confirm 정원 카페 수서']);

    const cancelled = await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {});
    expect(cancelled.statusCode).toBe(200);
    expect(cancelled.json()).toEqual({ id: meetup.id, status: 'CANCELLED' });
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json()).toEqual({ id: meetup.id, status: 'CANCELLED' });
    expect(agent).toEqual(['confirm 정원 카페 수서', 'cancel 정원 카페 수서']);
    expect(sms).toEqual([]);
    expect((await ctx.app.inject({ method: 'GET', url: '/v1/reservations' })).json().reservations).toEqual([]);
    expect(await rows(ctx, 'SELECT status, reservation_id FROM meetups')).toEqual([{ status: 'CANCELLED', reservation_id: null }]);
  });

  it('문자 약속: 시각과 장소를 문자로 알리고, 취소하면 정정 문자를 보낸다', async () => {
    const { gateways, agent, sms } = recordingGateways();
    const ctx = await context({ gateways });
    const { seoyeon } = await peers(ctx);
    const meetup = (await book(ctx, seoyeon, 'SMS')).json();
    expect(meetup.mode).toBe('SMS');
    expect((await post(ctx, `/v1/meetups/${meetup.id}/share`, {}, 'meet-2-share')).json().status).toBe('SHARED');
    const time = meetTimeText(new Date(meetup.meetAt));
    expect(sms[1]).toBe(`PitCrew 알림 · ${time}에 ${meetup.placeName}에서 만나요. 두 분 모두 비슷하게 걸리는 곳으로 정했어요.`);
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json()).toEqual({ id: meetup.id, status: 'CANCELLED' });
    expect(sms[2]).toBe(`PitCrew 알림 · ${time} ${meetup.placeName} 약속은 취소됐어요.`);
    expect(agent).toEqual([]);
  });

  it('알리기 전에 취소하면 일행에게 아무것도 보내지 않는다', async () => {
    const { gateways, agent } = recordingGateways();
    const ctx = await context({ gateways });
    const { minsu } = await peers(ctx);
    const meetup = (await book(ctx, minsu, 'AGENT')).json();
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json().status).toBe('CANCELLED');
    expect(agent).toEqual([]);
  });

  it('같은 키로 다시 잡으면 같은 약속을 돌려주고 예약도 하나다', async () => {
    const ctx = await context();
    const { minsu } = await peers(ctx);
    const plan = (await post(ctx, '/v1/meetups/plan', { contactId: minsu })).json();
    const payload = { contactId: minsu, placeId: plan.place.id, meetAt: plan.meetAt, mode: 'AGENT' };
    const first = await post(ctx, '/v1/meetups', payload, 'meet-3-book');
    const second = await post(ctx, '/v1/meetups', payload, 'meet-3-book');
    expect(first.statusCode).toBe(201);
    expect(second.json()).toEqual(first.json());
    expect(await rows(ctx, 'SELECT COUNT(*) AS n FROM reservations')).toEqual([{ n: 1 }]);
  });

  it('에이전트가 없는 일행을 에이전트 약속으로 잡으면 409, 없는 장소는 404', async () => {
    const ctx = await context();
    const { seoyeon } = await peers(ctx);
    const meetAt = new Date(Date.now() + 3_600_000).toISOString();
    const res = await post(ctx, '/v1/meetups', { contactId: seoyeon, placeId: 'mp-sinchon', meetAt, mode: 'AGENT' });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('NO_PEER_AGENT');
    expect((await post(ctx, '/v1/meetups', { contactId: seoyeon, placeId: 'nope', meetAt, mode: 'SMS' })).statusCode).toBe(404);
  });

  it('상대 에이전트가 거절하면 409 PEER_REJECTED이고 취소는 할 수 있다', async () => {
    const base = mockGateways();
    const peerAgents: PeerAgentGateway = { provider: 'mock', confirm: async () => ({ accepted: false, reason: '그 시간엔 회의가 생겼어요' }), cancel: base.peerAgents.cancel };
    const ctx = await context({ gateways: { ...base, peerAgents } });
    const { minsu } = await peers(ctx);
    const meetup = (await book(ctx, minsu, 'AGENT')).json();
    const res = await post(ctx, `/v1/meetups/${meetup.id}/share`, {});
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toEqual({ code: 'PEER_REJECTED', message: '그 시간엔 회의가 생겼어요' });
    expect(await rows(ctx, 'SELECT status FROM meetups')).toEqual([{ status: 'REJECTED' }]);
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json().status).toBe('CANCELLED');
  });

  it('상대 에이전트가 응답하지 않으면 504와 결과 미확인, 취소 때도 못 알리면 note로 알려 준다', async () => {
    const peerAgents: PeerAgentGateway = { provider: 'mock', confirm: never, cancel: never };
    const ctx = await context({ gateways: { ...mockGateways(), peerAgents }, gatewayTimeoutMs: 50 });
    const { minsu } = await peers(ctx);
    const meetup = (await book(ctx, minsu, 'AGENT')).json();
    const res = await post(ctx, `/v1/meetups/${meetup.id}/share`, {});
    expect(res.statusCode).toBe(504);
    expect(res.json().error.code).toBe('GATEWAY_TIMEOUT');
    expect(await rows(ctx, 'SELECT status FROM meetups')).toEqual([{ status: 'SHARE_UNKNOWN' }]);
    expect((await post(ctx, `/v1/meetups/${meetup.id}/share`, {})).json().error.code).toBe('NOT_SHAREABLE');

    const note = '김민수님에게 취소를 알리지 못했어요. 직접 연락해 주세요';
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json()).toEqual({ id: meetup.id, status: 'CANCELLED', note });
    expect((await post(ctx, `/v1/meetups/${meetup.id}/cancel`, {})).json()).toEqual({ id: meetup.id, status: 'CANCELLED', note });
    expect((await ctx.app.inject({ method: 'GET', url: '/v1/reservations' })).json().reservations).toEqual([]);
  });

  it('없는 약속은 404', async () => {
    const ctx = await context();
    expect((await post(ctx, `/v1/meetups/${randomUUID()}/share`, {})).statusCode).toBe(404);
    expect((await post(ctx, `/v1/meetups/${randomUUID()}/cancel`, {})).statusCode).toBe(404);
  });
});

describe('문자 시각', () => {
  it('한국 시각 HH:mm, 날짜가 다르면 내일을 붙인다', () => {
    const now = new Date('2026-11-05T09:00:00Z');
    expect(meetTimeText(new Date('2026-11-05T10:05:00Z'), now)).toBe('19:05');
    expect(meetTimeText(new Date('2026-11-05T15:30:00Z'), now)).toBe('내일 00:30');
  });
});
