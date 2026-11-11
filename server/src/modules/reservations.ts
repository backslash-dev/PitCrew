import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { PoolConnection, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { HttpError, notFound } from '../http/errors.js';
import { idSchema, instantSchema } from '../http/validation.js';
import { gatewayFailure, withTimeout } from '../adapters/timeout.js';
import { idempotent, saveResult } from '../http/idempotency.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';

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

/** 호출한 쪽의 트랜잭션 안에서 부른다. */
export async function saveReservation(
  connection: PoolConnection,
  id: string,
  reservation: { placeName: string; reservedAt: Date; partySize: number; companionIds: readonly string[] },
) {
  await connection.execute(
    `INSERT INTO reservations (id, place_name, reserved_at, party_size) VALUES (?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE place_name = VALUES(place_name), reserved_at = VALUES(reserved_at), party_size = VALUES(party_size)`,
    [id, reservation.placeName, reservation.reservedAt, reservation.partySize],
  );
  await connection.execute('DELETE FROM reservation_companions WHERE reservation_id = ?', [id]);
  const companions = [...new Set(reservation.companionIds)];
  if (companions.length > 0) {
    const [known] = await connection.query<RowDataPacket[]>('SELECT id FROM contacts WHERE id IN (?)', [companions]);
    if (known.length !== companions.length) throw new HttpError(400, 'UNKNOWN_CONTACT', '등록되지 않은 일행이 있습니다');
    await connection.query('INSERT INTO reservation_companions (reservation_id, contact_id) VALUES ?', [companions.map((contactId) => [id, contactId])]);
  }
}

export const reservationRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS }) => {
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
      await saveReservation(connection, id, body);
      await connection.commit();
      return await findReservation(connection, id);
    } catch (error) {
      await connection.rollback().catch(() => {});
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

  app.post('/reservations/:id/reschedule', async (request, reply) => {
    const { id } = Params.parse(request.params);
    const { reservedAt } = RescheduleBody.parse(request.body);
    return idempotent(db, request, reply, 'reschedule', async () => {
      const changeId = randomUUID();
      const current = await inTransaction(async (connection) => {
        const [rows] = await connection.execute<ReservationRow[]>(
          'SELECT id, place_name, reserved_at, party_size FROM reservations WHERE id = ? FOR UPDATE',
          [id],
        );
        const row = rows[0];
        if (!row) throw notFound('예약');
        await connection.execute(
          "INSERT INTO reservation_changes (id, reservation_id, previous_at, requested_at, status, provider) VALUES (?, ?, ?, ?, 'REQUESTED', ?)",
          [changeId, id, row.reserved_at, reservedAt, gateways.restaurants.provider],
        );
        return row;
      });

      let result;
      try {
        result = await withTimeout(
          gateways.restaurants.requestReschedule({ placeName: current.place_name, from: current.reserved_at, to: reservedAt, partySize: current.party_size }),
          gatewayTimeoutMs,
        );
      } catch (error) {
        request.log.warn({ err: error, changeId }, 'restaurant reschedule gateway failed');
        await setChangeStatus(changeId, 'UNKNOWN');
        throw gatewayFailure(error, '식당');
      }
      if (!result.accepted) {
        await setChangeStatus(changeId, 'REJECTED');
        throw new HttpError(409, 'RESCHEDULE_REJECTED', result.reason);
      }

      await saveResult(
        () =>
          inTransaction(async (connection) => {
            const [rows] = await connection.execute<ReservationRow[]>('SELECT reserved_at FROM reservations WHERE id = ? FOR UPDATE', [id]);
            // 식당을 기다리는 사이 앱에서 예약을 고쳤다면 덮어쓰지 않는다
            if (!rows[0] || rows[0].reserved_at.getTime() !== current.reserved_at.getTime()) {
              await connection.execute("UPDATE reservation_changes SET status = 'CONFLICT' WHERE id = ?", [changeId]);
              return;
            }
            await connection.execute("UPDATE reservation_changes SET status = 'ACCEPTED' WHERE id = ?", [changeId]);
            await connection.execute('UPDATE reservations SET reserved_at = ? WHERE id = ?', [reservedAt, id]);
          }),
        '식당은 변경을 받았을 수 있지만 결과를 저장하지 못했습니다',
      );
      const [changes] = await db.execute<ChangeRow[]>('SELECT status FROM reservation_changes WHERE id = ?', [changeId]);
      if (changes[0]?.status === 'CONFLICT') throw new HttpError(409, 'CHANGED_SINCE', '요청하는 동안 예약이 바뀌어 반영하지 않았습니다');
      return { status: 200, body: { ...(await findReservation(db, id)), changeId } };
    });
  });

  /** 그 뒤로 예약이 다시 바뀌었으면 엉뚱한 시각으로 돌아가지 않게 거절한다. */
  app.post('/reservation-changes/:id/revert', async (request) => {
    const { id } = Params.parse(request.params);
    const claimed = await inTransaction(async (connection) => {
      const [changes] = await connection.execute<ChangeRow[]>(
        'SELECT id, reservation_id, previous_at, requested_at, status FROM reservation_changes WHERE id = ? FOR UPDATE',
        [id],
      );
      const change = changes[0];
      if (!change) throw notFound('예약 변경');
      if (change.status === 'REVERTED') return { change, current: null };
      if (change.status === 'REVERTING') throw new HttpError(409, 'IN_PROGRESS', '이 변경을 되돌리는 중입니다');
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
      await connection.execute("UPDATE reservation_changes SET status = 'REVERTING' WHERE id = ?", [id]);
      return { change, current };
    });
    const { change, current } = claimed;
    if (current === null) return await findReservation(db, change.reservation_id);

    let result;
    try {
      result = await withTimeout(
        gateways.restaurants.revertReschedule({ placeName: current.place_name, current: current.reserved_at, restoreTo: change.previous_at, partySize: current.party_size }),
        gatewayTimeoutMs,
      );
    } catch (error) {
      request.log.warn({ err: error, changeId: id }, 'restaurant revert gateway failed');
      await setChangeStatus(id, 'REVERT_UNKNOWN');
      throw gatewayFailure(error, '식당');
    }
    if (!result.accepted) {
      await setChangeStatus(id, 'ACCEPTED');
      throw new HttpError(409, 'REVERT_REJECTED', result.reason);
    }
    await saveResult(
      () =>
        inTransaction(async (connection) => {
          await connection.execute("UPDATE reservation_changes SET status = 'REVERTED', reverted_at = CURRENT_TIMESTAMP(3) WHERE id = ?", [id]);
          await connection.execute('UPDATE reservations SET reserved_at = ? WHERE id = ?', [change.previous_at, change.reservation_id]);
        }),
      '식당은 되돌렸을 수 있지만 결과를 저장하지 못했습니다',
    );
    return await findReservation(db, change.reservation_id);
  });

  async function setChangeStatus(id: string, status: string) {
    await db.execute('UPDATE reservation_changes SET status = ? WHERE id = ?', [status, id]);
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
