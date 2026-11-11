import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { gatewayFailure, withTimeout } from '../adapters/timeout.js';
import { HttpError, notFound } from '../http/errors.js';
import { idempotent, saveResult } from '../http/idempotency.js';
import { idSchema } from '../http/validation.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';

const WaitlistBody = z.object({
  restaurantId: z.string().min(1).max(64),
  partySize: z.number().int().min(1).max(20),
});
const Params = z.object({ id: idSchema });

interface ChargerRow extends RowDataPacket {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  detour_minutes: number;
  power_kw: number;
  available_stalls: number;
}

interface RestaurantRow extends RowDataPacket {
  id: string;
  charger_id: string;
  name: string;
  walk_minutes: number;
  wait_minutes: number;
}

interface WaitlistRow extends RowDataPacket {
  id: string;
  restaurant_id: string;
  restaurant_name: string;
  party_size: number;
  queue_position: number;
  wait_minutes: number;
  status: string;
  created_at: Date;
}

const waitlistJson = (row: Pick<WaitlistRow, 'id' | 'restaurant_id' | 'party_size' | 'queue_position' | 'wait_minutes' | 'status'>) => ({
  id: row.id,
  restaurantId: row.restaurant_id,
  partySize: row.party_size,
  position: row.queue_position,
  waitMinutes: row.wait_minutes,
  status: row.status,
});

export const chargingRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS }) => {
  app.get('/chargers', async () => {
    const [chargers] = await db.query<ChargerRow[]>(
      'SELECT id, name, latitude, longitude, detour_minutes, power_kw, available_stalls FROM chargers WHERE active = 1 ORDER BY detour_minutes, id',
    );
    const [restaurants] = await db.query<RestaurantRow[]>(
      'SELECT id, charger_id, name, walk_minutes, wait_minutes FROM charger_restaurants ORDER BY walk_minutes, id',
    );
    return {
      chargers: chargers.map((c) => ({
        id: c.id,
        name: c.name,
        latitude: Number(c.latitude),
        longitude: Number(c.longitude),
        detourMinutes: c.detour_minutes,
        powerKw: c.power_kw,
        availableStalls: c.available_stalls,
        restaurants: restaurants
          .filter((r) => r.charger_id === c.id)
          .map((r) => ({ id: r.id, name: r.name, walkMinutes: r.walk_minutes, waitMinutes: r.wait_minutes })),
      })),
    };
  });

  app.post('/waitlists', async (request, reply) => {
    const body = WaitlistBody.parse(request.body);
    const [rows] = await db.execute<RestaurantRow[]>(
      'SELECT id, charger_id, name, walk_minutes, wait_minutes FROM charger_restaurants WHERE id = ?',
      [body.restaurantId],
    );
    const restaurant = rows[0];
    if (!restaurant) throw notFound('식당');

    return idempotent(db, request, reply, 'waitlists', async () => {
      const id = randomUUID();
      await db.execute(
        'INSERT INTO waitlists (id, restaurant_id, party_size, queue_position, wait_minutes, status, provider) VALUES (?, ?, ?, 0, 0, ?, ?)',
        [id, restaurant.id, body.partySize, 'PENDING', gateways.waitlists.provider],
      );
      let result;
      try {
        result = await withTimeout(
          gateways.waitlists.register({ restaurantName: restaurant.name, partySize: body.partySize, expectedWaitMinutes: restaurant.wait_minutes }),
          gatewayTimeoutMs,
        );
      } catch (error) {
        request.log.warn({ err: error, waitlistId: id }, 'waitlist gateway failed');
        await setStatus(id, 'UNKNOWN', 'gateway failure or timeout');
        throw gatewayFailure(error, '식당 줄서기');
      }
      if (!result.accepted) {
        await setStatus(id, 'REJECTED', result.reason);
        throw new HttpError(409, 'WAITLIST_REJECTED', result.reason);
      }
      await saveResult(
        () => db.execute("UPDATE waitlists SET status = 'WAITING', queue_position = ?, wait_minutes = ? WHERE id = ?", [result.position, result.waitMinutes, id]),
        '줄서기는 등록됐을 수 있지만 결과를 저장하지 못했습니다',
      );
      return {
        status: 201,
        body: waitlistJson({ id, restaurant_id: restaurant.id, party_size: body.partySize, queue_position: result.position, wait_minutes: result.waitMinutes, status: 'WAITING' }),
      };
    });
  });

  app.post('/waitlists/:id/cancel', async (request) => {
    const { id } = Params.parse(request.params);
    const waitlist = await claim(id);
    if (waitlist.status === 'CANCELLED') return waitlistJson(waitlist);

    let result;
    try {
      result = await withTimeout(gateways.waitlists.cancel({ restaurantName: waitlist.restaurant_name, registeredAt: waitlist.created_at }), gatewayTimeoutMs);
    } catch (error) {
      request.log.warn({ err: error, waitlistId: id }, 'waitlist cancel gateway failed');
      await setStatus(id, 'CANCEL_UNKNOWN', 'gateway failure or timeout');
      throw gatewayFailure(error, '식당 줄서기');
    }
    if (!result.accepted) {
      await setStatus(id, 'WAITING', null);
      throw new HttpError(409, 'CANCEL_REJECTED', result.reason);
    }
    await saveResult(
      () => db.execute("UPDATE waitlists SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3), failure = NULL WHERE id = ?", [id]),
      '줄서기는 취소됐을 수 있지만 결과를 저장하지 못했습니다',
    );
    return waitlistJson({ ...waitlist, status: 'CANCELLED' });
  });

  async function claim(id: string): Promise<WaitlistRow> {
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [rows] = await connection.execute<WaitlistRow[]>(
        `SELECT w.id, w.restaurant_id, r.name AS restaurant_name, w.party_size, w.queue_position, w.wait_minutes, w.status, w.created_at
         FROM waitlists w JOIN charger_restaurants r ON r.id = w.restaurant_id WHERE w.id = ? FOR UPDATE OF w`,
        [id],
      );
      const waitlist = rows[0];
      if (!waitlist) throw notFound('줄서기');
      if (waitlist.status === 'CANCELLING') throw new HttpError(409, 'IN_PROGRESS', '이 줄서기의 취소를 처리하는 중입니다');
      if (waitlist.status !== 'WAITING' && waitlist.status !== 'CANCELLED') throw new HttpError(409, 'NOT_CANCELLABLE', '취소할 수 있는 줄서기가 아닙니다');
      if (waitlist.status === 'WAITING') await connection.execute("UPDATE waitlists SET status = 'CANCELLING' WHERE id = ?", [id]);
      await connection.commit();
      return waitlist;
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  }

  async function setStatus(id: string, status: string, failure: string | null) {
    await db.execute('UPDATE waitlists SET status = ?, failure = ? WHERE id = ?', [status, failure?.slice(0, 255) ?? null, id]);
  }
};
