import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { HttpError } from '../http/errors.js';
import { idSchema } from '../http/validation.js';
import type { Deps } from './deps.js';

const SendBody = z.object({
  message: z.string().trim().min(1).max(300),
  contactIds: z.array(idSchema).min(1).max(20),
});
const ListQuery = z.object({ limit: z.coerce.number().int().min(1).max(100).default(20) });

interface ContactRow extends RowDataPacket {
  id: string;
  name: string;
  phone: string;
}

interface NotificationRow extends RowDataPacket {
  id: string;
  recipient_name: string;
  recipient_phone: string;
  message: string;
  status: string;
  provider: string;
  error: string | null;
  created_at: Date;
}

export const notificationRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways }) => {
  /** 일행에게 알림을 보낸다. 받는 사람마다 결과를 따로 돌려주고, 발송 내역은 실패도 남긴다. */
  app.post('/notifications', async (request) => {
    const body = SendBody.parse(request.body);
    const ids = [...new Set(body.contactIds)];
    const [contacts] = await db.query<ContactRow[]>('SELECT id, name, phone FROM contacts WHERE id IN (?)', [ids]);
    if (contacts.length !== ids.length) throw new HttpError(400, 'UNKNOWN_CONTACT', '등록되지 않은 일행이 있습니다');

    const results = [];
    for (const contact of contacts) {
      const sent = await gateways.notifier
        .send({ name: contact.name, phone: contact.phone, text: body.message })
        .catch((error: unknown) => ({ ok: false as const, error: error instanceof Error ? error.message : String(error) }));
      await db.execute(
        `INSERT INTO notifications (id, recipient_name, recipient_phone, message, status, provider, provider_message_id, error)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          randomUUID(),
          contact.name,
          contact.phone,
          body.message,
          sent.ok ? 'SENT' : 'FAILED',
          gateways.notifier.provider,
          sent.ok ? sent.messageId : null,
          sent.ok ? null : sent.error.slice(0, 255),
        ],
      );
      results.push({ contactId: contact.id, name: contact.name, status: sent.ok ? 'SENT' : 'FAILED' });
    }
    return { results };
  });

  app.get('/notifications', async (request) => {
    const { limit } = ListQuery.parse(request.query);
    const [rows] = await db.query<NotificationRow[]>(
      'SELECT id, recipient_name, recipient_phone, message, status, provider, error, created_at FROM notifications ORDER BY created_at DESC LIMIT ?',
      [limit],
    );
    return {
      notifications: rows.map((row) => ({
        id: row.id,
        recipientName: row.recipient_name,
        recipientPhone: row.recipient_phone,
        message: row.message,
        status: row.status,
        provider: row.provider,
        error: row.error,
        createdAt: row.created_at.toISOString(),
      })),
    };
  });
};
