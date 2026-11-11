import { randomUUID } from 'node:crypto';
import type { FastifyPluginAsync } from 'fastify';
import type { Pool, RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import type { Notifier, SendResult } from '../adapters/gateways.js';
import { GatewayTimeoutError, withTimeout } from '../adapters/timeout.js';
import { HttpError } from '../http/errors.js';
import { idempotencyKey } from '../http/idempotency.js';
import { idSchema } from '../http/validation.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';

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

interface SentRow extends RowDataPacket {
  id: string;
  contact_id: string;
  status: string;
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

export const maskPhone = (phone: string) => phone.replace(/^(\d{3})-(\d{3,4})-(\d{4})$/, (_, a: string, b: string, c: string) => `${a}-${'*'.repeat(b.length)}-${c}`);

/** 실패도 FAILED로 기록한 뒤 던져, 부르는 쪽이 결과 미확인으로 다루게 한다. */
export async function sendRecorded(
  db: Pool,
  notifier: Notifier,
  timeoutMs: number,
  contact: { id: string; name: string; phone: string },
  text: string,
): Promise<SendResult> {
  const id = randomUUID();
  await db.execute(
    `INSERT INTO notifications (id, recipient_name, recipient_phone, message, status, provider, contact_id) VALUES (?, ?, ?, ?, 'QUEUED', ?, ?)`,
    [id, contact.name, contact.phone, text, notifier.provider, contact.id],
  );
  let sent: SendResult;
  try {
    sent = await withTimeout(notifier.send({ name: contact.name, phone: contact.phone, text }), timeoutMs);
  } catch (error) {
    await db.execute("UPDATE notifications SET status = 'FAILED', error = ? WHERE id = ?", [error instanceof GatewayTimeoutError ? 'TIMEOUT' : 'PROVIDER_ERROR', id]);
    throw error;
  }
  await db.execute('UPDATE notifications SET status = ?, provider_message_id = ?, error = ? WHERE id = ?', [
    sent.ok ? 'SENT' : 'FAILED',
    sent.ok ? sent.messageId : null,
    sent.ok ? null : sent.error.slice(0, 255),
    id,
  ]);
  return sent;
}

export const notificationRoutes: FastifyPluginAsync<Deps> = async (
  app,
  { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS, notifyLimitPerMinute = 10 },
) => {
  app.post('/notifications', { config: { rateLimit: { max: notifyLimitPerMinute, timeWindow: '1 minute' } } }, async (request) => {
    const body = SendBody.parse(request.body);
    const requestKey = idempotencyKey(request);
    const ids = [...new Set(body.contactIds)];
    const [contacts] = await db.query<ContactRow[]>('SELECT id, name, phone FROM contacts WHERE id IN (?)', [ids]);
    if (contacts.length !== ids.length) throw new HttpError(400, 'UNKNOWN_CONTACT', '등록되지 않은 일행이 있습니다');

    const previous = new Map<string, SentRow>();
    if (requestKey !== null) {
      const [rows] = await db.query<SentRow[]>('SELECT id, contact_id, status FROM notifications WHERE request_key = ? AND contact_id IN (?)', [requestKey, ids]);
      rows.forEach((row) => previous.set(row.contact_id, row));
    }

    const results = [];
    for (const contact of contacts) {
      const earlier = previous.get(contact.id);
      if (earlier?.status === 'SENT') {
        results.push({ contactId: contact.id, name: contact.name, status: 'SENT' });
        continue;
      }
      // 발송 뒤 저장에 실패해도 무엇을 보내려 했는지 남게 먼저 기록한다
      const id = earlier?.id ?? randomUUID();
      if (!earlier) {
        try {
          await db.execute(
            `INSERT INTO notifications (id, recipient_name, recipient_phone, message, status, provider, contact_id, request_key)
             VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?)`,
            [id, contact.name, contact.phone, body.message, gateways.notifier.provider, contact.id, requestKey],
          );
        } catch (error) {
          // 같은 요청이 동시에 들어오면 먼저 들어온 쪽만 보낸다
          if ((error as { code?: string }).code === 'ER_DUP_ENTRY') throw new HttpError(409, 'IN_PROGRESS', '같은 요청을 처리하는 중입니다');
          throw error;
        }
      }
      const sent = await withTimeout(gateways.notifier.send({ name: contact.name, phone: contact.phone, text: body.message }), gatewayTimeoutMs).catch(
        (error: unknown) => {
          request.log.warn({ err: error, notificationId: id }, 'notifier failed');
          // 공급자 오류 원문에는 내부 정보가 섞일 수 있어 정해진 코드만 쓴다
          return { ok: false as const, error: error instanceof GatewayTimeoutError ? 'TIMEOUT' : 'PROVIDER_ERROR' };
        },
      );
      await db.execute('UPDATE notifications SET status = ?, provider_message_id = ?, error = ? WHERE id = ?', [
        sent.ok ? 'SENT' : 'FAILED',
        sent.ok ? sent.messageId : null,
        sent.ok ? null : sent.error.slice(0, 255),
        id,
      ]);
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
        recipientPhone: maskPhone(row.recipient_phone),
        message: row.message,
        status: row.status,
        provider: row.provider,
        error: row.error,
        createdAt: row.created_at.toISOString(),
      })),
    };
  });
};
