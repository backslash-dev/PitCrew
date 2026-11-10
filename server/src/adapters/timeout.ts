import { HttpError } from '../http/errors.js';

export class GatewayTimeoutError extends Error {}

/** 외부 시스템이 멈춰도 요청과 커넥션이 묶여 있지 않게 시간 제한을 둔다. 시간이 지나도 외부 처리는 일어났을 수 있다. */
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

/** 외부 호출 실패를 사용자 문장과 상태로 바꾼다. 결과를 모르는 경우라 다시 보내지 말라는 뜻을 담는다. */
export function gatewayFailure(error: unknown, what: string): HttpError {
  if (error instanceof GatewayTimeoutError) {
    return new HttpError(504, 'GATEWAY_TIMEOUT', `${what} 응답이 늦어 결과를 확인하지 못했습니다`);
  }
  return new HttpError(502, 'GATEWAY_ERROR', `${what}에 연결하지 못했습니다`);
}
