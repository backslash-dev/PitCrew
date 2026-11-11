import type { FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import type { Deps } from './deps.js';

const Query = z.object({ name: z.string().trim().max(50).optional() });

export interface StoreRow extends RowDataPacket {
  id: string;
  name: string;
  branch: string;
  latitude: number;
  longitude: number;
  detour_minutes: number;
  prep_minutes: number;
}

export const storeJson = (row: StoreRow) => ({
  id: row.id,
  name: row.name,
  branch: row.branch,
  latitude: row.latitude,
  longitude: row.longitude,
  detourMinutes: row.detour_minutes,
  prepMinutes: row.prep_minutes,
});

export const storeRoutes: FastifyPluginAsync<Deps> = async (app, { db }) => {
  app.get('/stores', async (request) => {
    const { name } = Query.parse(request.query);
    const [rows] = await db.execute<StoreRow[]>(
      'SELECT id, name, branch, latitude, longitude, detour_minutes, prep_minutes FROM stores WHERE active = 1 ORDER BY detour_minutes, id',
    );
    const query = name?.replace(/\s/g, '');
    const matched = query ? rows.filter((row) => `${row.name}${row.branch}`.replace(/\s/g, '').includes(query)) : rows;
    return { stores: matched.map(storeJson) };
  });
};
