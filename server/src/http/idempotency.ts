import { createHash } from 'node:crypto';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Pool, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { HttpError } from './errors.js';

const KEY_PATTERN = /^[A-Za-z0-9._:-]{1,100}$/;

export interface Outcome {
  status: number;
  body: unknown;
}

interface KeyRow extends RowDataPacket {
  request_hash: string;
  status_code: number | null;
  response_body: string | null;
}

/** 요청의 Idempotency-Key 헤더. 없으면 null, 형식이 틀리면 400. */
export function idempotencyKey(request: FastifyRequest): string | null {
  const value = request.headers['idempotency-key'];
  if (value === undefined) return null;
  if (typeof value !== 'string' || !KEY_PATTERN.test(value)) {
    throw new HttpError(400, 'INVALID_IDEMPOTENCY_KEY', 'Idempotency-Key 형식이 올바르지 않습니다');
  }
  return value;
}

/**
 * 같은 키로 다시 온 요청에는 처음 결과를 그대로 돌려준다. 처리 중이면 409, 내용이 다르면 422.
 * 처리 로직이 낸 HttpError도 결과로 저장한다(외부 시스템이 거절했거나 결과를 모르는 경우 다시 보내지 않게).
 * 예상하지 못한 오류(버그·DB 장애)일 때만 키를 지워 다시 시도할 수 있게 한다.
 */
export async function idempotent(
  db: Pool,
  request: FastifyRequest,
  reply: FastifyReply,
  scope: string,
  run: () => Promise<Outcome>,
): Promise<FastifyReply> {
  const key = idempotencyKey(request);
  if (key === null) return send(reply, await run());

  const hash = createHash('sha256').update(`${request.method} ${request.url}\n${JSON.stringify(request.body ?? null)}`).digest('hex');
  const [inserted] = await db.execute<ResultSetHeader>(
    'INSERT IGNORE INTO idempotency_keys (scope, idem_key, request_hash) VALUES (?, ?, ?)',
    [scope, key, hash],
  );
  if (inserted.affectedRows === 0) {
    const [rows] = await db.execute<KeyRow[]>('SELECT request_hash, status_code, response_body FROM idempotency_keys WHERE scope = ? AND idem_key = ?', [scope, key]);
    const saved = rows[0];
    if (!saved) throw new HttpError(409, 'IN_PROGRESS', '같은 요청을 처리하는 중입니다');
    if (saved.request_hash !== hash) throw new HttpError(422, 'IDEMPOTENCY_MISMATCH', '같은 Idempotency-Key로 다른 요청을 보냈습니다');
    if (saved.status_code === null) throw new HttpError(409, 'IN_PROGRESS', '같은 요청을 처리하는 중입니다');
    return reply.status(saved.status_code).send(saved.response_body === null ? undefined : JSON.parse(saved.response_body));
  }

  let outcome: Outcome;
  try {
    outcome = await run();
  } catch (error) {
    if (!(error instanceof HttpError)) {
      await db.execute('DELETE FROM idempotency_keys WHERE scope = ? AND idem_key = ?', [scope, key]).catch(() => {});
      throw error;
    }
    outcome = { status: error.statusCode, body: { error: { code: error.code, message: error.message } } };
  }
  await db.execute('UPDATE idempotency_keys SET status_code = ?, response_body = ? WHERE scope = ? AND idem_key = ?', [
    outcome.status,
    outcome.body === undefined ? null : JSON.stringify(outcome.body),
    scope,
    key,
  ]);
  return send(reply, outcome);
}

function send(reply: FastifyReply, outcome: Outcome) {
  return reply.status(outcome.status).send(outcome.body);
}

/** 외부 처리는 끝났는데 결과 저장이 실패하면, 재시도로 다시 처리되지 않게 결과 미확인 오류로 남긴다. */
export async function saveResult(write: () => Promise<unknown>, message: string) {
  try {
    await write();
  } catch (error) {
    throw new HttpError(500, 'RESULT_NOT_SAVED', message, error);
  }
}
