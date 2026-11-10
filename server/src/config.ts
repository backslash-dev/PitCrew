import { z } from 'zod';

const LOOPBACK = new Set(['127.0.0.1', 'localhost', '::1']);

const EnvSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    // 에뮬레이터는 10.0.2.2로 호스트 루프백에 붙으므로 로컬 개발은 127.0.0.1로 충분하다
    HOST: z.string().min(1).default('127.0.0.1'),
    PORT: z.coerce.number().int().min(1).max(65535).default(3000),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
    DATABASE_URL: z.string().startsWith('mysql://').default('mysql://pitcrew:pitcrew@127.0.0.1:3308/pitcrew'),
    API_TOKEN: z.preprocess((value) => (value === '' ? undefined : value), z.string().min(16).optional()),
    RATE_LIMIT_PER_MINUTE: z.coerce.number().int().min(1).default(120),
    // 문자 발송은 비용과 스팸 위험이 있어 따로 더 낮게 제한한다
    NOTIFY_LIMIT_PER_MINUTE: z.coerce.number().int().min(1).default(10),
    TRUST_PROXY: z.enum(['true', 'false']).default('false').transform((v) => v === 'true'),
    GATEWAY_TIMEOUT_MS: z.coerce.number().int().min(100).max(60_000).default(5_000),
  })
  .refine((env) => env.NODE_ENV !== 'production' || env.API_TOKEN !== undefined, {
    message: '운영 환경에서는 API_TOKEN이 필요합니다',
    path: ['API_TOKEN'],
  })
  // NODE_ENV를 빠뜨리고 외부에 열어도 인증 없이 공개되지 않게, 루프백이 아닌 주소에서는 토큰을 요구한다
  .refine((env) => LOOPBACK.has(env.HOST) || env.API_TOKEN !== undefined, {
    message: '외부에 여는 HOST에서는 API_TOKEN이 필요합니다',
    path: ['API_TOKEN'],
  });

export type Config = z.infer<typeof EnvSchema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    throw new Error(`환경변수 오류\n${z.prettifyError(parsed.error)}`);
  }
  return parsed.data;
}
