import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { HttpError, notFound } from '../http/errors.js';
import { instantSchema } from '../http/validation.js';
import type { Deps } from './deps.js';
import type { StoreRow } from './stores.js';

const OrderBody = z.object({
  storeId: z.string().min(1).max(64),
  item: z.string().trim().min(1).max(100),
  pickupAt: instantSchema,
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
};
