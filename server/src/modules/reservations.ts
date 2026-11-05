import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { PoolConnection, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { HttpError, notFound } from '../http/errors.js';
import { idSchema, instantSchema } from '../http/validation.js';
import type { Deps } from './deps.js';

const ReservationBody = z.object({
  placeName: z.string().trim().min(1).max(100),
  reservedAt: instantSchema,
  partySize: z.number().int().min(1).max(50),
  companionIds: z.array(idSchema).max(20).default([]),
});
const RescheduleBody = z.object({ reservedAt: instantSchema });
const Params = z.object({ id: idSchema });

interface ChangeRow extends RowDataPacket {
  id: string;
  reservation_id: string;
  previous_at: Date;
  requested_at: Date;
  status: string;
}

interface ReservationRow extends RowDataPacket {
  id: string;
  place_name: string;
  reserved_at: Date;
  party_size: number;
  companion_ids: string | null;
}

const SELECT_RESERVATIONS = `
  SELECT r.id, r.place_name, r.reserved_at, r.party_size, GROUP_CONCAT(c.contact_id ORDER BY c.contact_id) AS companion_ids
  FROM reservations r LEFT JOIN reservation_companions c ON c.reservation_id = r.id`;

const toJson = (row: ReservationRow) => ({
  id: row.id,
  placeName: row.place_name,
  reservedAt: row.reserved_at.toISOString(),
  partySize: row.party_size,
  companionIds: row.companion_ids ? row.companion_ids.split(',') : [],
});

async function findReservation(db: Deps['db'] | PoolConnection, id: string) {
  const [rows] = await db.execute<ReservationRow[]>(`${SELECT_RESERVATIONS} WHERE r.id = ? GROUP BY r.id`, [id]);
  return rows[0] ? toJson(rows[0]) : null;
}

export const reservationRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways }) => {
  app.get('/reservations', async () => {
    const [rows] = await db.execute<ReservationRow[]>(`${SELECT_RESERVATIONS} GROUP BY r.id ORDER BY r.reserved_at`);
    return { reservations: rows.map(toJson) };
  });

  app.put('/reservations/:id', async (request) => {
    const { id } = Params.parse(request.params);
    const body = ReservationBody.parse(request.body);
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      await connection.execute(
        `INSERT INTO reservations (id, place_name, reserved_at, party_size) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE place_name = VALUES(place_name), reserved_at = VALUES(reserved_at), party_size = VALUES(party_size)`,
        [id, body.placeName, body.reservedAt, body.partySize],
      );
      await connection.execute('DELETE FROM reservation_companions WHERE reservation_id = ?', [id]);
      const companions = [...new Set(body.companionIds)];
      if (companions.length > 0) {
        const [known] = await connection.query<RowDataPacket[]>('SELECT id FROM contacts WHERE id IN (?)', [companions]);
        if (known.length !== companions.length) throw new HttpError(400, 'UNKNOWN_CONTACT', '등록되지 않은 일행이 있습니다');
        await connection.query('INSERT INTO reservation_companions (reservation_id, contact_id) VALUES ?', [
          companions.map((contactId) => [id, contactId]),
        ]);
      }
      await connection.commit();
      return await findReservation(connection, id);
    } catch (error) {
      await connection.rollback();
      throw error;
    } finally {
      connection.release();
    }
  });

  app.delete('/reservations/:id', async (request, reply) => {
    const { id } = Params.parse(request.params);
    const [result] = await db.execute<ResultSetHeader>('DELETE FROM reservations WHERE id = ?', [id]);
    if (result.affectedRows === 0) throw notFound('예약');
    return reply.status(204).send();
  });

  /** 식당에 시간 변경을 요청하고, 받아들여지면 예약 시각을 바꾼다. 요청 결과는 성패와 관계없이 남긴다. */
  app.post('/reservations/:id/reschedule', async (request) => {
    const { id } = Params.parse(request.params);
    const { reservedAt } = RescheduleBody.parse(request.body);
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [rows] = await connection.execute<ReservationRow[]>(
        'SELECT id, place_name, reserved_at, party_size FROM reservations WHERE id = ? FOR UPDATE',
        [id],
      );
      const current = rows[0];
      if (!current) throw notFound('예약');
      const result = await gateways.restaurants.requestReschedule({
        placeName: current.place_name,
        from: current.reserved_at,
        to: reservedAt,
        partySize: current.party_size,
      });
      const changeId = randomUUID();
      await connection.execute(
        'INSERT INTO reservation_changes (id, reservation_id, previous_at, requested_at, status, provider) VALUES (?, ?, ?, ?, ?, ?)',
        [changeId, id, current.reserved_at, reservedAt, result.accepted ? 'ACCEPTED' : 'REJECTED', gateways.restaurants.provider],
      );
      if (result.accepted) await connection.execute('UPDATE reservations SET reserved_at = ? WHERE id = ?', [reservedAt, id]);
      await connection.commit();
      if (!result.accepted) throw new HttpError(409, 'RESCHEDULE_REJECTED', result.reason);
      return { ...(await findReservation(db, id)), changeId };
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  });

  /**
   * 받아들여진 변경을 무르고 원래 시각으로 되돌린다. 그 뒤로 예약이 다시 바뀌었으면 엉뚱한 시각으로 돌아가지 않게 거절한다.
   */
  app.post('/reservation-changes/:id/revert', async (request) => {
    const { id } = Params.parse(request.params);
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [changes] = await connection.execute<ChangeRow[]>(
        'SELECT id, reservation_id, previous_at, requested_at, status FROM reservation_changes WHERE id = ? FOR UPDATE',
        [id],
      );
      const change = changes[0];
      if (!change) throw notFound('예약 변경');
      if (change.status !== 'ACCEPTED') throw new HttpError(409, 'NOT_REVERTIBLE', '되돌릴 수 있는 변경이 아닙니다');
      const [rows] = await connection.execute<ReservationRow[]>(
        'SELECT id, place_name, reserved_at, party_size FROM reservations WHERE id = ? FOR UPDATE',
        [change.reservation_id],
      );
      const current = rows[0];
      if (!current) throw notFound('예약');
      if (current.reserved_at.getTime() !== change.requested_at.getTime()) {
        throw new HttpError(409, 'CHANGED_SINCE', '그 뒤로 예약이 다시 바뀌어 되돌릴 수 없습니다');
      }
      const result = await gateways.restaurants.revertReschedule({
        placeName: current.place_name,
        current: current.reserved_at,
        restoreTo: change.previous_at,
        partySize: current.party_size,
      });
      if (!result.accepted) throw new HttpError(409, 'REVERT_REJECTED', result.reason);
      await connection.execute("UPDATE reservation_changes SET status = 'REVERTED', reverted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", [id]);
      await connection.execute('UPDATE reservations SET reserved_at = ? WHERE id = ?', [change.previous_at, current.id]);
      await connection.commit();
      return await findReservation(db, current.id);
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  });
};
