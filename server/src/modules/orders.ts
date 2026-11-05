import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { HttpError, notFound } from '../http/errors.js';
import { idSchema, instantSchema } from '../http/validation.js';
import type { Deps } from './deps.js';
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

const toJson = (row: OrderRow) => ({
  id: row.id,
  storeId: row.store_id,
  storeName: `${row.store_name} ${row.store_branch}`,
  item: row.item,
  pickupAt: row.pickup_at.toISOString(),
  status: row.status,
  createdAt: row.created_at.toISOString(),
});

export const orderRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways }) => {
  app.post('/orders', async (request, reply) => {
    const body = OrderBody.parse(request.body);
    const [rows] = await db.execute<StoreRow[]>(
      'SELECT id, name, branch, latitude, longitude, detour_minutes, prep_minutes FROM stores WHERE id = ? AND active = 1',
      [body.storeId],
    );
    const store = rows[0];
    if (!store) throw notFound('매장');
    const result = await gateways.storeOrders.placeOrder({
      storeName: `${store.name} ${store.branch}`,
      item: body.item,
      pickupAt: body.pickupAt,
      prepMinutes: store.prep_minutes,
    });
    if (!result.accepted) throw new HttpError(409, 'ORDER_REJECTED', result.reason);
    const id = randomUUID();
    await db.execute('INSERT INTO orders (id, store_id, item, pickup_at, status, provider) VALUES (?, ?, ?, ?, ?, ?)', [
      id,
      store.id,
      body.item,
      body.pickupAt,
      'ACCEPTED',
      gateways.storeOrders.provider,
    ]);
    return reply.status(201).send({
      id,
      storeId: store.id,
      item: body.item,
      pickupAt: body.pickupAt.toISOString(),
      status: 'ACCEPTED',
    });
  });

  /** 최근 주문 이력. 앱이 자주 시키는 메뉴(단골 루틴)를 찾는 근거로 쓴다. */
  app.get('/orders', async (request) => {
    const { limit } = ListQuery.parse(request.query);
    const [rows] = await db.query<OrderRow[]>(`${SELECT_ORDERS} ORDER BY o.pickup_at DESC LIMIT ?`, [limit]);
    return { orders: rows.map(toJson) };
  });

  app.post('/orders/:id/cancel', async (request) => {
    const { id } = Params.parse(request.params);
    const connection = await db.getConnection();
    try {
      await connection.beginTransaction();
      const [rows] = await connection.execute<OrderRow[]>(`${SELECT_ORDERS} WHERE o.id = ? FOR UPDATE`, [id]);
      const order = rows[0];
      if (!order) throw notFound('주문');
      if (order.status !== 'ACCEPTED') throw new HttpError(409, 'NOT_CANCELLABLE', '취소할 수 있는 주문이 아닙니다');
      const result = await gateways.storeOrders.cancelOrder({
        storeName: `${order.store_name} ${order.store_branch}`,
        item: order.item,
        placedAt: order.created_at,
        pickupAt: order.pickup_at,
      });
      if (!result.accepted) throw new HttpError(409, 'CANCEL_REJECTED', result.reason);
      await connection.execute("UPDATE orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3) WHERE id = ?", [id]);
      await connection.commit();
      return toJson({ ...order, status: 'CANCELLED' });
    } catch (error) {
      await connection.rollback().catch(() => {});
      throw error;
    } finally {
      connection.release();
    }
  });
};
