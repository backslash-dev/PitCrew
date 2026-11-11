import type { FastifyPluginAsync } from 'fastify';
import type { ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { notFound } from '../http/errors.js';
import { idSchema, phoneSchema } from '../http/validation.js';
import type { Deps } from './deps.js';

const ContactBody = z.object({ name: z.string().trim().min(1).max(50), phone: phoneSchema });
const Params = z.object({ id: idSchema });

interface ContactRow extends RowDataPacket {
  id: string;
  name: string;
  phone: string;
}

const toJson = (row: ContactRow) => ({ id: row.id, name: row.name, phone: row.phone });

export const contactRoutes: FastifyPluginAsync<Deps> = async (app, { db }) => {
  app.get('/contacts', async () => {
    const [rows] = await db.execute<ContactRow[]>('SELECT id, name, phone FROM contacts ORDER BY name');
    return { contacts: rows.map(toJson) };
  });

  // 앱이 만든 id로 저장해 다시 보내도 결과가 같다
  app.put('/contacts/:id', async (request) => {
    const { id } = Params.parse(request.params);
    const body = ContactBody.parse(request.body);
    await db.execute(
      'INSERT INTO contacts (id, name, phone) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone)',
      [id, body.name, body.phone],
    );
    return { id, ...body };
  });

  app.delete('/contacts/:id', async (request, reply) => {
    const { id } = Params.parse(request.params);
    const [result] = await db.execute<ResultSetHeader>('DELETE FROM contacts WHERE id = ?', [id]);
    if (result.affectedRows === 0) throw notFound('일행');
    return reply.status(204).send();
  });
};
