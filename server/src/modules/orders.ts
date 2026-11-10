import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { gatewayFailure, withTimeout } from '../adapters/timeout.js';
import { HttpError, notFound } from '../http/errors.js';
import { idempotent, saveResult } from '../http/idempotency.js';
import { idSchema, instantSchema } from '../http/validation.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';
import type { StoreRow } from './stores.js';

const OrderBody = z.object({
  storeId: z.string().min(1).max(64),
  item: z.string().trim().min(1).max(100),
  pickupAt: instantSchema,
});
const Params = z.object({ id: idSchema });
const ListQuery = z.object({ limit: z.coerce.number().int().min(1).max(200).default(50) });

interface OrderRow extends RowDataPacket {
  id: string;
  store_id: string;
  store_name: string;
  store_branch: string;
  item: string;
  pickup_at: Date;
  status: string;
  created_at: Date;
}

const SELECT_ORDERS = `
  SELECT o.id, o.store_id, s.name AS store_name, s.branch AS store_branch, o.item, o.pickup_at, o.status, o.created_at
  FROM orders o JOIN stores s ON s.id = o.store_id`;

const toJson = (row: Pick<OrderRow, 'id' | 'store_id' | 'store_name' | 'store_branch' | 'item' | 'pickup_at' | 'status' | 'created_at'>) => ({
  id: row.id,
  storeId: row.store_id,
  storeName: `${row.store_name} ${row.store_branch}`,
  item: row.item,
  pickupAt: row.pickup_at.toISOString(),
  status: row.status,
  createdAt: row.created_at.toISOString(),
});

/**
 * 주문 상태: PENDING(매장 응답 대기) → ACCEPTED | REJECTED | UNKNOWN(응답 없음), ACCEPTED → CANCELLING → CANCELLED.
 * 매장 호출은 트랜잭션 밖에서 하고, 호출 전후로 상태를 기록해 매장과 DB가 어긋나도 흔적이 남게 한다.
 */
export const orderRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS }) => {
  app.post('/orders', async (request, reply) => {
    const body = OrderBody.parse(request.body);
    const [rows] = await db.execute<StoreRow[]>(
      'SELECT id, name, branch, latitude, longitude, detour_minutes, prep_minutes FROM stores WHERE id = ? AND active = 1',
      [body.storeId],
    );
    const store = rows[0];
    if (!store) throw notFound('매장');

    return idempotent(db, request, reply, 'orders', async () => {
      const id = randomUUID();
      await db.execute('INSERT INTO orders (id, store_id, item, pickup_at, status, provider) VALUES (?, ?, ?, ?, ?, ?)', [
        id,
        store.id,
        body.item,
        body.pickupAt,
        'PENDING',
        gateways.storeOrders.provider,
      ]);
      let result;
      try {
        result = await withTimeout(
          gateways.storeOrders.placeOrder({ storeName: `${store.name} ${store.branch}`, item: body.item, pickupAt: body.pickupAt, prepMinutes: store.prep_minutes }),
          gatewayTimeoutMs,
        );
      } catch (error) {
        request.log.warn({ err: error, orderId: id }, 'store order gateway failed');
        await setStatus(id, 'UNKNOWN', 'gateway failure or timeout');
        throw gatewayFailure(error, '매장');
      }
      if (!result.accepted) {
        await setStatus(id, 'REJECTED', result.reason);
        throw new HttpError(409, 'ORDER_REJECTED', result.reason);
      }
      await saveResult(() => setStatus(id, 'ACCEPTED', null), '주문은 접수됐을 수 있지만 결과를 저장하지 못했습니다');
      return { status: 201, body: { id, storeId: store.id, item: body.item, pickupAt: body.pickupAt.toISOString(), status: 'ACCEPTED' } };
    });
  });

  /** 최근 주문 이력. 앱이 자주 시키는 메뉴(단골 루틴)를 찾는 근거로 쓴다. */
  app.get('/orders', async (request) => {
    const { limit } = ListQuery.parse(request.query);
    const [rows] = await db.query<OrderRow[]>(`${SELECT_ORDERS} ORDER BY o.pickup_at DESC LIMIT ?`, [limit]);
    return { orders: rows.map(toJson) };
  });

  /** 이미 취소된 주문을 다시 취소하면(응답 유실 뒤 재시도) 성공으로 본다. */
  app.post('/orders/:id/cancel', async (request) => {
    const { id } = Params.parse(request.params);
    const order = await claim(id);
    if (order.status === 'CANCELLED') return toJson(order);

    let result;
    try {
      result = await withTimeout(
        gateways.storeOrders.cancelOrder({ storeName: `${order.store_name} ${order.store_branch}`, item: order.item, placedAt: order.created_at, pickupAt: order.pickup_at }),
        gatewayTimeoutMs,
      );
    } catch (error) {
      request.log.warn({ err: error, orderId: id }, 'store cancel gateway failed');
      await setStatus(id, 'CANCEL_UNKNOWN', 'gateway failure or timeout');
      throw gatewayFailure(error, '매장');
    }
    if (!result.accepted) {
      await setStatus(id, 'ACCEPTED', null);
      throw new HttpError(409, 'CANCEL_REJECTED', result.reason);
    }
    await saveResult(
      () => db.execute("UPDATE orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3), failure = NULL WHERE id = ?", [id]),
      '주문은 취소됐을 수 있지만 결과를 저장하지 못했습니다',
    );
    return toJson({ ...order, status: 'CANCELLED' });
  });

  // 취소를 진행할 권리를 잡는다. 동시에 두 번 취소해도 매장에는 한 번만 요청한다
  async function claim(id: string): Promise<OrderRow> {
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [rows] = await connection.execute<OrderRow[]>(`${SELECT_ORDERS} WHERE o.id = ? FOR UPDATE OF o`, [id]);
      const order = rows[0];
      if (!order) throw notFound('주문');
      if (order.status === 'CANCELLING') throw new HttpError(409, 'IN_PROGRESS', '이 주문의 취소를 처리하는 중입니다');
      if (order.status !== 'ACCEPTED' && order.status !== 'CANCELLED') throw new HttpError(409, 'NOT_CANCELLABLE', '취소할 수 있는 주문이 아닙니다');
      if (order.status === 'ACCEPTED') await connection.execute("UPDATE orders SET status = 'CANCELLING' WHERE id = ?", [id]);
      await connection.commit();
      return order;
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  }

  async function setStatus(id: string, status: string, failure: string | null) {
    await db.execute<ResultSetHeader>('UPDATE orders SET status = ?, failure = ? WHERE id = ?', [status, failure?.slice(0, 255) ?? null, id]);
  }
};
