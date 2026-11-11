import type { FastifyError, FastifyInstance } from 'fastify';
import { z } from 'zod';

export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    message: string,
    cause?: unknown,
  ) {
    super(message, { cause });
  }
}

export const notFound = (what: string) => new HttpError(404, 'NOT_FOUND', `${what}을(를) 찾을 수 없습니다`);

/** 내부 오류 내용은 밖으로 내보내지 않는다. */
export function registerErrorHandler(app: FastifyInstance) {
  app.setErrorHandler((error: FastifyError | Error, request, reply) => {
    if (error instanceof HttpError) {
      if (error.statusCode >= 500) request.log.error(error, error.code);
      return reply.status(error.statusCode).send({ error: { code: error.code, message: error.message } });
    }
    if (error instanceof z.ZodError) {
      return reply.status(400).send({ error: { code: 'INVALID_REQUEST', message: z.prettifyError(error) } });
    }
    const status = 'statusCode' in error && typeof error.statusCode === 'number' ? error.statusCode : 500;
    if (status >= 500) {
      request.log.error(error, 'unhandled error');
      return reply.status(500).send({ error: { code: 'INTERNAL', message: '서버 오류가 발생했습니다' } });
    }
    return reply.status(status).send({ error: { code: 'BAD_REQUEST', message: error.message } });
  });
  app.setNotFoundHandler((_request, reply) =>
    reply.status(404).send({ error: { code: 'NOT_FOUND', message: '없는 경로입니다' } }),
  );
}
