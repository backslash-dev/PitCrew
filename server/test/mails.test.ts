import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import { afterEach, describe, expect, it } from 'vitest';
import { MAIL_SIGNATURE, MockMailAssistant, mockGateways, replyIntent, type Gateways } from '../src/adapters/gateways.js';
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

async function rows(ctx: TestContext, sql: string, params: unknown[] = []) {
  const [result] = await ctx.db.query<RowDataPacket[]>(sql, params);
  return result;
}

async function mail(ctx: TestContext, minutesAgo: number, subject = '내일 주간 회의 10시로 당겨도 될까요') {
  const id = randomUUID();
  await ctx.db.execute('INSERT INTO mails (id, sender_name, sender_email, subject, body, received_at) VALUES (?, ?, ?, ?, ?, ?)', [
    id,
    '정하늘',
    'haneul.jung@example.com',
    subject,
    '안녕하세요, 정하늘입니다.\n고객사 일정 때문에 회의를 당기면 좋겠습니다. 오늘 중으로 회신 부탁드립니다.',
    new Date(Date.now() - minutesAgo * 60_000),
  ]);
  return id;
}

const get = (ctx: TestContext, url: string) => ctx.app.inject({ method: 'GET', url });
const post = (ctx: TestContext, url: string, payload: Record<string, unknown>, key?: string) =>
  ctx.app.inject({ method: 'POST', url, payload, ...(key ? { headers: { 'idempotency-key': key } } : {}) });

describe('메일 목록', () => {
  it('최신순이고 요약은 처음 한 번만 만들어 저장한다', async () => {
    let calls = 0;
    const assistant = new MockMailAssistant();
    const gateways: Gateways = { ...mockGateways(), mailAssistant: { provider: 'mock', summarize: (m) => (calls++, assistant.summarize(m)), draftReply: (r) => assistant.draftReply(r) } };
    const ctx = await context({ gateways });
    const older = await mail(ctx, 30, '견적서 최종본 확인 부탁드립니다');
    const newer = await mail(ctx, 3);
    const first = (await get(ctx, '/v1/mails?unreplied=true')).json().mails;
    expect(first.map((m: { id: string }) => m.id)).toEqual([newer, older]);
    expect(first[0]).toEqual({
      id: newer,
      from: { name: '정하늘', email: 'haneul.jung@example.com' },
      subject: '내일 주간 회의 10시로 당겨도 될까요',
      summary: '내일 주간 회의 10시로 당겨도 될까요. 고객사 일정 때문에 회의를 당기면 좋겠습니다.',
      receivedAt: expect.any(String),
      replied: false,
    });
    expect(calls).toBe(2);
    await get(ctx, '/v1/mails');
    expect(calls).toBe(2);
    expect(await rows(ctx, 'SELECT COUNT(*) AS n FROM mails WHERE summary IS NOT NULL')).toEqual([{ n: 2 }]);
  });

  it('요약을 만들지 못하면 제목으로 대신하고 저장하지 않는다', async () => {
    const gateways: Gateways = { ...mockGateways(), mailAssistant: { provider: 'mock', summarize: never, draftReply: never } };
    const ctx = await context({ gateways, gatewayTimeoutMs: 50 });
    await mail(ctx, 1);
    expect((await get(ctx, '/v1/mails')).json().mails[0].summary).toBe('내일 주간 회의 10시로 당겨도 될까요');
    expect(await rows(ctx, 'SELECT summary FROM mails')).toEqual([{ summary: null }]);
  });

  it('긴 요약은 80자 안으로 줄인다', async () => {
    const summary = await new MockMailAssistant().summarize({ subject: '가'.repeat(60), body: '나'.repeat(60) + '.' });
    expect(summary.length).toBeLessThanOrEqual(80);
    expect(summary.endsWith('…')).toBe(true);
  });
});

describe('답장 초안', () => {
  it('말한 지시에서 의도를 고른다', () => {
    expect(replyIntent('괜찮다고 해줘')).toBe('accept');
    expect(replyIntent('그렇게 하자고 해')).toBe('accept');
    expect(replyIntent('오늘은 어렵다고 해줘')).toBe('decline');
    expect(replyIntent('그건 안 돼')).toBe('decline');
    expect(replyIntent('불가능하다고 해')).toBe('decline');
    expect(replyIntent('괜찮지 않다고, 힘들다고 해')).toBe('decline');
    expect(replyIntent('좀 늦는다고 해줘')).toBe('late');
    expect(replyIntent('도착하고 답장한다고 해')).toBe('later');
    expect(replyIntent('알겠다고 해')).toBe('other');
  });

  it('보낸 사람 이름으로 시작하고 서명으로 끝난다', async () => {
    const ctx = await context();
    const id = await mail(ctx, 1);
    const res = await post(ctx, `/v1/mails/${id}/draft`, { instruction: '좋아요, 그렇게 하자고 해줘' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ draft: `정하늘님, 말씀하신 대로 진행해 주세요. 감사합니다.\n\n${MAIL_SIGNATURE}` });
    const later = (await post(ctx, `/v1/mails/${id}/draft`, { instruction: '운전 중이라 나중에' })).json().draft;
    expect(later).toBe(`정하늘님, 운전 중이라 도착하는 대로 자세히 답장드리겠습니다.\n\n${MAIL_SIGNATURE}`);
    expect((await post(ctx, `/v1/mails/${id}/draft`, { instruction: '' })).statusCode).toBe(400);
    expect((await post(ctx, `/v1/mails/${randomUUID()}/draft`, { instruction: '괜찮아' })).statusCode).toBe(404);
  });

  it('초안 도우미가 늦으면 504', async () => {
    const gateways: Gateways = { ...mockGateways(), mailAssistant: { provider: 'mock', summarize: never, draftReply: never } };
    const ctx = await context({ gateways, gatewayTimeoutMs: 50 });
    const id = await mail(ctx, 1);
    const res = await post(ctx, `/v1/mails/${id}/draft`, { instruction: '괜찮아' });
    expect(res.statusCode).toBe(504);
    expect(res.json().error.code).toBe('GATEWAY_TIMEOUT');
  });
});

describe('답장 보내기', () => {
  it('Re: 제목으로 보내고 읽음으로 표시한다. 같은 키로 다시 보내도 한 번만 보낸다', async () => {
    const sent: { to: string; subject: string }[] = [];
    const base = mockGateways();
    const gateways: Gateways = { ...base, mailer: { provider: 'mock', send: (m) => (sent.push({ to: m.to, subject: m.subject }), base.mailer.send(m)) } };
    const ctx = await context({ gateways });
    const id = await mail(ctx, 1);
    const payload = { body: `정하늘님, 말씀하신 대로 진행해 주세요.\n\n${MAIL_SIGNATURE}`, instruction: '괜찮아' };
    const first = await post(ctx, `/v1/mails/${id}/reply`, payload, 'mail-1-reply');
    expect(first.statusCode).toBe(201);
    expect(first.json()).toEqual({ id: expect.any(String), mailId: id, status: 'SENT' });
    const second = await post(ctx, `/v1/mails/${id}/reply`, payload, 'mail-1-reply');
    expect(second.json()).toEqual(first.json());
    expect(sent).toEqual([{ to: 'haneul.jung@example.com', subject: 'Re: 내일 주간 회의 10시로 당겨도 될까요' }]);
    expect(await rows(ctx, 'SELECT status, instruction FROM mail_replies')).toEqual([{ status: 'SENT', instruction: '괜찮아' }]);
    expect((await rows(ctx, 'SELECT read_at FROM mails'))[0]!.read_at).toBeInstanceOf(Date);
    expect((await get(ctx, '/v1/mails')).json().mails[0].replied).toBe(true);
    expect((await get(ctx, '/v1/mails?unreplied=true')).json().mails).toEqual([]);
  });

  it('메일 서버가 응답하지 않으면 504와 결과 미확인으로 남고, 답장한 메일로 보지 않는다', async () => {
    const gateways: Gateways = { ...mockGateways(), mailer: { provider: 'mock', send: never } };
    const ctx = await context({ gateways, gatewayTimeoutMs: 50 });
    const id = await mail(ctx, 1);
    const res = await post(ctx, `/v1/mails/${id}/reply`, { body: '확인했습니다.' });
    expect(res.statusCode).toBe(504);
    expect(res.json().error.code).toBe('GATEWAY_TIMEOUT');
    expect(await rows(ctx, 'SELECT status FROM mail_replies')).toEqual([{ status: 'UNKNOWN' }]);
    expect((await get(ctx, '/v1/mails?unreplied=true')).json().mails).toHaveLength(1);
  });

  it('메일 서버가 거절하면 502이고 실패로 남는다', async () => {
    const gateways: Gateways = { ...mockGateways(), mailer: { provider: 'mock', send: async () => ({ ok: false, error: 'mailbox full' }) } };
    const ctx = await context({ gateways });
    const id = await mail(ctx, 1);
    expect((await post(ctx, `/v1/mails/${id}/reply`, { body: '확인했습니다.' })).statusCode).toBe(502);
    expect(await rows(ctx, 'SELECT status, failure FROM mail_replies')).toEqual([{ status: 'FAILED', failure: 'mailbox full' }]);
  });
});
