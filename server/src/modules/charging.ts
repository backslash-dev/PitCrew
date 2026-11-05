import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { HttpError, notFound } from '../http/errors.js';
import { idSchema } from '../http/validation.js';
import type { Deps } from './deps.js';

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

/** 경로상 충전소와 걸어갈 수 있는 식당, 식당 원격 줄서기. 실제 충전소·줄서기 API 전까지 카탈로그와 목업 게이트웨이로 동작한다. */
export const chargingRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways }) => {
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
    const result = await gateways.waitlists.register({
      restaurantName: restaurant.name,
      partySize: body.partySize,
      expectedWaitMinutes: restaurant.wait_minutes,
    });
    if (!result.accepted) throw new HttpError(409, 'WAITLIST_REJECTED', result.reason);
    const id = randomUUID();
    await db.execute(
      'INSERT INTO waitlists (id, restaurant_id, party_size, queue_position, wait_minutes, status, provider) VALUES (?, ?, ?, ?, ?, ?, ?)',
      [id, restaurant.id, body.partySize, result.position, result.waitMinutes, 'WAITING', gateways.waitlists.provider],
    );
    return reply.status(201).send(
      waitlistJson({
        id,
        restaurant_id: restaurant.id,
        party_size: body.partySize,
        queue_position: result.position,
        wait_minutes: result.waitMinutes,
        status: 'WAITING',
      }),
    );
  });

  app.post('/waitlists/:id/cancel', async (request) => {
    const { id } = Params.parse(request.params);
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [rows] = await connection.execute<WaitlistRow[]>(
        `SELECT w.id, w.restaurant_id, r.name AS restaurant_name, w.party_size, w.queue_position, w.wait_minutes, w.status, w.created_at
         FROM waitlists w JOIN charger_restaurants r ON r.id = w.restaurant_id WHERE w.id = ? FOR UPDATE`,
        [id],
      );
      const waitlist = rows[0];
      if (!waitlist) throw notFound('줄서기');
      if (waitlist.status !== 'WAITING') throw new HttpError(409, 'NOT_CANCELLABLE', '취소할 수 있는 줄서기가 아닙니다');
      const result = await gateways.waitlists.cancel({ restaurantName: waitlist.restaurant_name, registeredAt: waitlist.created_at });
      if (!result.accepted) throw new HttpError(409, 'CANCEL_REJECTED', result.reason);
      await connection.execute("UPDATE waitlists SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3) WHERE id = ?", [id]);
      await connection.commit();
      return waitlistJson({ ...waitlist, status: 'CANCELLED' });
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  });
};
