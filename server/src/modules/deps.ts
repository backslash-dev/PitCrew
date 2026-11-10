import type { Pool } from 'mysql2/promise';
import type { Gateways } from '../adapters/gateways.js';

export interface Deps {
  db: Pool;
  gateways: Gateways;
  /** 외부 시스템 한 번 호출의 시간 제한. */
  gatewayTimeoutMs?: number;
  /** 알림 발송 요청의 분당 한도. */
  notifyLimitPerMinute?: number;
}

export const DEFAULT_GATEWAY_TIMEOUT_MS = 5_000;
