import { HttpError } from '../http/errors.js';

export class GatewayTimeoutError extends Error {}

/** 시간이 지나도 외부 처리는 일어났을 수 있어 결과 미확인으로 다룬다. */
export async function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new GatewayTimeoutError(`gateway timed out after ${ms}ms`)), ms);
  });
  try {
    return await Promise.race([work, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

export function gatewayFailure(error: unknown, what: string): HttpError {
  if (error instanceof GatewayTimeoutError) {
    return new HttpError(504, 'GATEWAY_TIMEOUT', `${what} 응답이 늦어 결과를 확인하지 못했습니다`);
  }
  return new HttpError(502, 'GATEWAY_ERROR', `${what}에 연결하지 못했습니다`);
}
