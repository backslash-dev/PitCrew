import { randomUUID } from 'node:crypto';
import type { FastifyBaseLogger, FastifyPluginAsync } from 'fastify';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { gatewayFailure, withTimeout } from '../adapters/timeout.js';
import { notFound } from '../http/errors.js';
import { idempotent, saveResult } from '../http/idempotency.js';
import { idSchema } from '../http/validation.js';
import { DEFAULT_GATEWAY_TIMEOUT_MS, type Deps } from './deps.js';

const ListQuery = z.object({ unreplied: z.enum(['true', 'false']).default('false').transform((value) => value === 'true') });
const DraftBody = z.object({ instruction: z.string().trim().min(1).max(200) });
const ReplyBody = z.object({ body: z.string().trim().min(1).max(2000), instruction: z.string().trim().min(1).max(200).optional() });
const Params = z.object({ id: idSchema });
const LIST_LIMIT = 20;

interface MailRow extends RowDataPacket {
  id: string;
  sender_name: string;
  sender_email: string;
  subject: string;
  body: string;
  summary: string | null;
  received_at: Date;
  replied: number;
}

const REPLIED = "EXISTS (SELECT 1 FROM mail_replies r WHERE r.mail_id = m.id AND r.status = 'SENT')";

const replySubject = (subject: string) => (/^re:/i.test(subject.trim()) ? subject.trim() : `Re: ${subject.trim()}`);

export const mailRoutes: FastifyPluginAsync<Deps> = async (app, { db, gateways, gatewayTimeoutMs = DEFAULT_GATEWAY_TIMEOUT_MS }) => {
  app.get('/mails', async (request) => {
    const { unreplied } = ListQuery.parse(request.query);
    const [rows] = await db.query<MailRow[]>(
      `SELECT m.id, m.sender_name, m.sender_email, m.subject, m.body, m.summary, m.received_at, ${REPLIED} AS replied
       FROM mails m ${unreplied ? `WHERE NOT ${REPLIED}` : ''} ORDER BY m.received_at DESC, m.id LIMIT ?`,
      [LIST_LIMIT],
    );
    const summaries = await Promise.all(rows.map((row) => summaryOf(row, request.log)));
    return {
      mails: rows.map((row, index) => ({
        id: row.id,
        from: { name: row.sender_name, email: row.sender_email },
        subject: row.subject,
        summary: summaries[index],
        receivedAt: row.received_at.toISOString(),
        replied: Number(row.replied) === 1,
      })),
    };
  });

  app.post('/mails/:id/draft', async (request) => {
    const { id } = Params.parse(request.params);
    const { instruction } = DraftBody.parse(request.body);
    const mail = await findMail(id);
    try {
      const draft = await withTimeout(
        gateways.mailAssistant.draftReply({ senderName: mail.sender_name, subject: mail.subject, body: mail.body, instruction }),
        gatewayTimeoutMs,
      );
      return { draft };
    } catch (error) {
      request.log.warn({ err: error, mailId: id }, 'mail draft failed');
      throw gatewayFailure(error, '메일 도우미');
    }
  });

  app.post('/mails/:id/reply', async (request, reply) => {
    const { id } = Params.parse(request.params);
    const body = ReplyBody.parse(request.body);
    const mail = await findMail(id);

    return idempotent(db, request, reply, 'mail-replies', async () => {
      const replyId = randomUUID();
      await db.execute("INSERT INTO mail_replies (id, mail_id, instruction, body, status, provider) VALUES (?, ?, ?, ?, 'PENDING', ?)", [
        replyId,
        mail.id,
        body.instruction ?? null,
        body.body,
        gateways.mailer.provider,
      ]);
      let sent;
      try {
        sent = await withTimeout(gateways.mailer.send({ to: mail.sender_email, subject: replySubject(mail.subject), body: body.body }), gatewayTimeoutMs);
      } catch (error) {
        request.log.warn({ err: error, replyId }, 'mailer failed');
        await setStatus(replyId, 'UNKNOWN', 'mailer failure or timeout');
        throw gatewayFailure(error, '메일 서버');
      }
      if (!sent.ok) {
        await setStatus(replyId, 'FAILED', sent.error);
        throw gatewayFailure(null, '메일 서버');
      }
      await saveResult(async () => {
        await db.execute("UPDATE mail_replies SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), failure = NULL WHERE id = ?", [replyId]);
        await db.execute('UPDATE mails SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(3)) WHERE id = ?', [mail.id]);
      }, '메일은 보내졌을 수 있지만 결과를 저장하지 못했습니다');
      return { status: 201, body: { id: replyId, mailId: mail.id, status: 'SENT' } };
    });
  });

  /** 요약에 실패하면 저장하지 않아 다음 조회 때 다시 만든다. */
  async function summaryOf(row: MailRow, log: FastifyBaseLogger): Promise<string> {
    if (row.summary !== null) return row.summary;
    let summary: string;
    try {
      summary = (await withTimeout(gateways.mailAssistant.summarize({ senderName: row.sender_name, subject: row.subject, body: row.body }), gatewayTimeoutMs))
        .trim()
        .slice(0, 200);
    } catch (error) {
      log.warn({ err: error, mailId: row.id }, 'mail summary failed');
      return row.subject;
    }
    if (summary.length === 0) return row.subject;
    await db.execute('UPDATE mails SET summary = ? WHERE id = ? AND summary IS NULL', [summary, row.id]);
    return summary;
  }

  async function findMail(id: string): Promise<MailRow> {
    const [rows] = await db.execute<MailRow[]>('SELECT id, sender_name, sender_email, subject, body, summary, received_at, 0 AS replied FROM mails WHERE id = ?', [id]);
    const mail = rows[0];
    if (!mail) throw notFound('메일');
    return mail;
  }

  async function setStatus(id: string, status: string, failure: string | null) {
    await db.execute('UPDATE mail_replies SET status = ?, failure = ? WHERE id = ?', [status, failure?.slice(0, 255) ?? null, id]);
  }
};
