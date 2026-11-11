import { randomUUID } from 'node:crypto';
import type { FastifyBaseLogger, FastifyPluginAsync } from 'fastify';
import type { PoolConnection, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { GatewayTimeoutError, gatewayFailure, withTimeout } from '../adapters/timeout.js';
import { HttpError, notFound } from '../http/errors.js';
import { idempotent, saveResult } from '../http/idempotency.js';
import { idSchema, instantSchema } from '../http/validation.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';
import { BUFFER_MINUTES, chooseMeetAt, choosePlace, DEFAULT_ORIGIN, type Point } from './meetupPlanner.js';
import { sendRecorded } from './notifications.js';
import { saveReservation } from './reservations.js';

const PlanBody = z
  .object({
    contactId: idSchema,
    latitude: z.number().min(-90).max(90).optional(),
    longitude: z.number().min(-180).max(180).optional(),
    preferredAt: instantSchema.optional(),
  })
  .refine((body) => (body.latitude === undefined) === (body.longitude === undefined), {
    message: '위도와 경도는 함께 보내야 합니다',
    path: ['latitude'],
  });
type PlanRequest = z.infer<typeof PlanBody>;

const BookBody = z.object({
  contactId: idSchema,
  placeId: z.string().min(1).max(64),
  meetAt: instantSchema,
  mode: z.enum(['AGENT', 'SMS']),
});
const Params = z.object({ id: idSchema });

type Mode = 'AGENT' | 'SMS';

interface PeerRow extends RowDataPacket {
  id: string;
  name: string;
  phone: string;
  has_agent: number | null;
  latitude: number | null;
  longitude: number | null;
  free_from: Date | null;
  free_until: Date | null;
}

interface Profile {
  hasAgent: boolean;
  location: Point;
  freeFrom: Date;
  freeUntil: Date;
}

interface PlaceRow extends RowDataPacket {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
}

interface MeetupRow extends RowDataPacket {
  id: string;
  contact_id: string;
  contact_name: string;
  contact_phone: string;
  place_name: string;
  meet_at: Date;
  mode: Mode;
  status: string;
  reservation_id: string | null;
  failure: string | null;
}

const profileOf = (row: PeerRow): Profile | null =>
  row.has_agent === null || row.latitude === null || row.longitude === null || row.free_from === null || row.free_until === null
    ? null
    : { hasAgent: row.has_agent === 1, location: { latitude: row.latitude, longitude: row.longitude }, freeFrom: row.free_from, freeUntil: row.free_until };

const ASK_TEXT = 'PitCrew 알림 · 지금 계신 곳과 오늘 만날 수 있는 시간을 답장으로 알려 주세요. 두 분 모두 가기 편한 곳으로 약속을 잡아 드릴게요.';

const SEOUL_TIME = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const SEOUL_DATE = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' });

export function meetTimeText(at: Date, now: Date = new Date()): string {
  const time = SEOUL_TIME.format(at);
  return SEOUL_DATE.format(at) === SEOUL_DATE.format(now) ? time : `내일 ${time}`;
}

const cancelledJson = (id: string, note: string | null) => (note ? { id, status: 'CANCELLED', note } : { id, status: 'CANCELLED' });

export const meetupRoutes: FastifyPluginAsync<Deps> = async (
  app,
  { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS, notifyLimitPerMinute = 10 },
) => {
  app.post('/meetups/plan', async (request) => {
    const body = PlanBody.parse(request.body);
    const peer = await findPeer(body.contactId);
    const profile = profileOf(peer);
    if (!profile?.hasAgent) return { mode: 'SMS', contact: { id: peer.id, name: peer.name } };
    return plan(peer, profile, body, 'AGENT');
  });

  /** 문자를 보내므로 알림과 같은 분당 한도를 둔다. */
  app.post('/meetups/ask', { config: { rateLimit: { max: notifyLimitPerMinute, timeWindow: '1 minute' } } }, async (request, reply) => {
    const body = PlanBody.parse(request.body);
    const peer = await findPeer(body.contactId);
    const profile = profileOf(peer);
    if (profile?.hasAgent) return plan(peer, profile, body, 'AGENT');

    return idempotent(db, request, reply, 'meetup-ask', async () => {
      const requestId = randomUUID();
      await db.execute("INSERT INTO meetup_requests (id, contact_id, status, provider) VALUES (?, ?, 'ASKED', ?)", [
        requestId,
        peer.id,
        gateways.peerReplies.provider,
      ]);
      let sent;
      try {
        sent = await sendRecorded(db, gateways.notifier, gatewayTimeoutMs, peer, ASK_TEXT);
      } catch (error) {
        request.log.warn({ err: error, requestId }, 'meetup ask notifier failed');
        await setRequestStatus(requestId, 'UNKNOWN', 'notifier failure or timeout');
        throw gatewayFailure(error, '문자 서비스');
      }
      if (!sent.ok) {
        await setRequestStatus(requestId, 'FAILED', sent.error);
        throw gatewayFailure(null, '문자 서비스');
      }

      let answer;
      try {
        answer = await withTimeout(gateways.peerReplies.awaitReply({ contactName: peer.name, phone: peer.phone }), gatewayTimeoutMs);
      } catch (error) {
        if (!(error instanceof GatewayTimeoutError)) {
          request.log.warn({ err: error, requestId }, 'meetup reply gateway failed');
          await setRequestStatus(requestId, 'UNKNOWN', 'reply gateway failure');
          throw gatewayFailure(error, '문자 서비스');
        }
        answer = { replied: false as const, reason: 'timeout' };
      }
      // 위치·시간이 아직 반영되지 않았으면 답장이 없는 것으로 본다
      const replied = answer.replied ? profileOf(await findPeer(peer.id)) : null;
      if (!replied) {
        await setRequestStatus(requestId, 'NO_REPLY', answer.replied ? 'reply without profile' : answer.reason);
        throw new HttpError(504, 'NO_REPLY', '아직 답장이 없어요');
      }
      await db.execute("UPDATE meetup_requests SET status = 'REPLIED', replied_at = CURRENT_TIMESTAMP(3) WHERE id = ?", [requestId]);
      return { status: 200, body: await plan(peer, replied, body, 'SMS') };
    });
  });

  app.post('/meetups', async (request, reply) => {
    const body = BookBody.parse(request.body);
    const peer = await findPeer(body.contactId);
    const [places] = await db.execute<PlaceRow[]>('SELECT id, name, latitude, longitude FROM meetup_places WHERE id = ? AND active = 1', [body.placeId]);
    const place = places[0];
    if (!place) throw notFound('장소');
    if (body.mode === 'AGENT' && !profileOf(peer)?.hasAgent) throw new HttpError(409, 'NO_PEER_AGENT', `${peer.name}님은 에이전트를 쓰지 않아요`);

    return idempotent(db, request, reply, 'meetups', async () => {
      const id = randomUUID();
      const reservationId = randomUUID();
      await inTransaction(async (connection) => {
        await saveReservation(connection, reservationId, { placeName: place.name, reservedAt: body.meetAt, partySize: 2, companionIds: [peer.id] });
        await connection.execute(
          "INSERT INTO meetups (id, contact_id, place_id, meet_at, mode, status, reservation_id, provider) VALUES (?, ?, ?, ?, ?, 'BOOKED', ?, ?)",
          [id, peer.id, place.id, body.meetAt, body.mode, reservationId, body.mode === 'AGENT' ? gateways.peerAgents.provider : gateways.notifier.provider],
        );
      });
      return {
        status: 201,
        body: { id, reservationId, placeId: place.id, placeName: place.name, meetAt: body.meetAt.toISOString(), mode: body.mode, status: 'BOOKED' },
      };
    });
  });

  app.post('/meetups/:id/share', async (request, reply) => {
    const { id } = Params.parse(request.params);
    return idempotent(db, request, reply, 'meetup-share', async () => {
      const meetup = await claim(id, 'share');
      if (meetup.status === 'SHARED') return { status: 200, body: { id, status: 'SHARED' } };

      if (meetup.mode === 'AGENT') {
        let result;
        try {
          result = await withTimeout(gateways.peerAgents.confirm(peerTarget(meetup)), gatewayTimeoutMs);
        } catch (error) {
          request.log.warn({ err: error, meetupId: id }, 'peer agent confirm failed');
          await setStatus(id, 'SHARE_UNKNOWN', 'gateway failure or timeout');
          throw gatewayFailure(error, '상대 에이전트');
        }
        if (!result.accepted) {
          await setStatus(id, 'REJECTED', result.reason);
          throw new HttpError(409, 'PEER_REJECTED', result.reason);
        }
      } else {
        const text = `PitCrew 알림 · ${meetTimeText(meetup.meet_at)}에 ${meetup.place_name}에서 만나요. 두 분 모두 비슷하게 걸리는 곳으로 정했어요.`;
        let sent;
        try {
          sent = await sendRecorded(db, gateways.notifier, gatewayTimeoutMs, contactOf(meetup), text);
        } catch (error) {
          request.log.warn({ err: error, meetupId: id }, 'meetup share notifier failed');
          await setStatus(id, 'SHARE_UNKNOWN', 'notifier failure or timeout');
          throw gatewayFailure(error, '문자 서비스');
        }
        // 보내지지 않은 게 확실하므로 다시 공유할 수 있게 되돌린다
        if (!sent.ok) {
          await setStatus(id, 'BOOKED', sent.error);
          throw gatewayFailure(null, '문자 서비스');
        }
      }
      await saveResult(
        () => db.execute("UPDATE meetups SET status = 'SHARED', shared_at = CURRENT_TIMESTAMP(3), failure = NULL WHERE id = ?", [id]),
        '약속은 전해졌을 수 있지만 결과를 저장하지 못했습니다',
      );
      return { status: 200, body: { id, status: 'SHARED' } };
    });
  });

  /** 일행에게 알리지 못해도 취소는 진행하고 note로 알린다. */
  app.post('/meetups/:id/cancel', async (request) => {
    const { id } = Params.parse(request.params);
    const meetup = await claim(id, 'cancel');
    if (meetup.status === 'CANCELLED') return cancelledJson(id, meetup.failure);

    let note: string | null = null;
    if (meetup.status === 'SHARED' || meetup.status === 'SHARE_UNKNOWN') {
      const told = await tellCancelled(meetup, request.log);
      if (!told) note = `${meetup.contact_name}님에게 취소를 알리지 못했어요. 직접 연락해 주세요`;
    }
    await saveResult(
      () =>
        inTransaction(async (connection) => {
          if (meetup.reservation_id) await connection.execute('DELETE FROM reservations WHERE id = ?', [meetup.reservation_id]);
          await connection.execute("UPDATE meetups SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3), failure = ? WHERE id = ?", [note, id]);
        }),
      '약속은 취소됐을 수 있지만 결과를 저장하지 못했습니다',
    );
    return cancelledJson(id, note);
  });

  async function findPeer(contactId: string): Promise<PeerRow> {
    const [rows] = await db.execute<PeerRow[]>(
      `SELECT c.id, c.name, c.phone, p.has_agent, p.latitude, p.longitude, p.free_from, p.free_until
       FROM contacts c LEFT JOIN peer_profiles p ON p.contact_id = c.id WHERE c.id = ?`,
      [contactId],
    );
    const peer = rows[0];
    if (!peer) throw notFound('일행');
    return peer;
  }

  async function plan(peer: PeerRow, profile: Profile, body: PlanRequest, mode: Mode) {
    const [places] = await db.query<PlaceRow[]>('SELECT id, name, latitude, longitude FROM meetup_places WHERE active = 1 ORDER BY id');
    const origin = body.latitude !== undefined && body.longitude !== undefined ? { latitude: body.latitude, longitude: body.longitude } : DEFAULT_ORIGIN;
    const choice = choosePlace(origin, profile.location, places);
    if (!choice) throw new HttpError(409, 'NO_PLACE', '만날 만한 곳을 찾지 못했어요');

    const now = new Date();
    const buffer = BUFFER_MINUTES * 60_000;
    const [busy] = await db.execute<RowDataPacket[]>('SELECT reserved_at FROM reservations WHERE reserved_at BETWEEN ? AND ?', [
      new Date(now.getTime() - buffer),
      new Date(profile.freeUntil.getTime() + buffer),
    ]);
    const meetAt = chooseMeetAt({
      now,
      preferredAt: body.preferredAt,
      travelMinutes: Math.max(choice.userTravelMinutes, choice.peerTravelMinutes),
      freeFrom: profile.freeFrom,
      freeUntil: profile.freeUntil,
      busy: busy.map((row) => row.reserved_at as Date),
    });
    if (!meetAt) {
      throw new HttpError(409, 'PEER_UNAVAILABLE', body.preferredAt ? `${peer.name}님이 그 시간에는 어렵다고 해요` : `${peer.name}님이 오늘은 시간이 안 된다고 해요`);
    }
    const { place } = choice;
    return {
      mode,
      contact: { id: peer.id, name: peer.name },
      place: { id: place.id, name: place.name, latitude: place.latitude, longitude: place.longitude },
      meetAt: meetAt.toISOString(),
      userTravelMinutes: choice.userTravelMinutes,
      peerTravelMinutes: choice.peerTravelMinutes,
    };
  }

  async function claim(id: string, action: 'share' | 'cancel'): Promise<MeetupRow> {
    return inTransaction(async (connection) => {
      const [rows] = await connection.execute<MeetupRow[]>(
        `SELECT m.id, m.contact_id, c.name AS contact_name, c.phone AS contact_phone, p.name AS place_name, m.meet_at, m.mode, m.status, m.reservation_id, m.failure
         FROM meetups m JOIN contacts c ON c.id = m.contact_id JOIN meetup_places p ON p.id = m.place_id WHERE m.id = ? FOR UPDATE OF m`,
        [id],
      );
      const meetup = rows[0];
      if (!meetup) throw notFound('약속');
      if (meetup.status === 'SHARING' || meetup.status === 'CANCELLING') throw new HttpError(409, 'IN_PROGRESS', '이 약속을 처리하는 중입니다');
      if (action === 'share') {
        if (meetup.status === 'SHARED') return meetup;
        if (meetup.status !== 'BOOKED') throw new HttpError(409, 'NOT_SHAREABLE', '알릴 수 있는 약속이 아닙니다');
        await connection.execute("UPDATE meetups SET status = 'SHARING' WHERE id = ?", [id]);
      } else {
        if (meetup.status === 'CANCELLED') return meetup;
        await connection.execute("UPDATE meetups SET status = 'CANCELLING' WHERE id = ?", [id]);
      }
      return meetup;
    });
  }

  async function tellCancelled(meetup: MeetupRow, log: FastifyBaseLogger): Promise<boolean> {
    try {
      if (meetup.mode === 'AGENT') {
        const result = await withTimeout(gateways.peerAgents.cancel(peerTarget(meetup)), gatewayTimeoutMs);
        if (!result.accepted) log.warn({ meetupId: meetup.id, reason: result.reason }, 'peer agent refused cancel');
        return result.accepted;
      }
      const text = `PitCrew 알림 · ${meetTimeText(meetup.meet_at)} ${meetup.place_name} 약속은 취소됐어요.`;
      const sent = await sendRecorded(db, gateways.notifier, gatewayTimeoutMs, contactOf(meetup), text);
      return sent.ok;
    } catch (error) {
      log.warn({ err: error, meetupId: meetup.id }, 'meetup cancel notice failed');
      return false;
    }
  }

  const peerTarget = (meetup: MeetupRow) => ({ contactName: meetup.contact_name, placeName: meetup.place_name, meetAt: meetup.meet_at });
  const contactOf = (meetup: MeetupRow) => ({ id: meetup.contact_id, name: meetup.contact_name, phone: meetup.contact_phone });

  async function setStatus(id: string, status: string, failure: string | null) {
    await db.execute('UPDATE meetups SET status = ?, failure = ? WHERE id = ?', [status, failure?.slice(0, 255) ?? null, id]);
  }

  async function setRequestStatus(id: string, status: string, failure: string | null) {
    await db.execute('UPDATE meetup_requests SET status = ?, failure = ? WHERE id = ?', [status, failure?.slice(0, 255) ?? null, id]);
  }

  async function inTransaction<T>(work: (connection: PoolConnection) => Promise<T>): Promise<T> {
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const result = await work(connection);
      await connection.commit();
      return result;
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  }
};
