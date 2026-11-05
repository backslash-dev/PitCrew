import { z } from 'zod';

const EnvSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    // 에뮬레이터는 10.0.2.2로 호스트 루프백에 붙으므로 로컬 개발은 127.0.0.1로 충분하다
    HOST: z.string().min(1).default('127.0.0.1'),
    PORT: z.coerce.number().int().min(1).max(65535).default(3000),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
    DATABASE_URL: z.string().startsWith('mysql://').default('mysql://pitcrew:pitcrew@127.0.0.1:3308/pitcrew'),
    API_TOKEN: z.preprocess((value) => (value === '' ? undefined : value), z.string().min(16).optional()),
  })
  .refine((env) => env.NODE_ENV !== 'production' || env.API_TOKEN !== undefined, {
    message: '운영 환경에서는 API_TOKEN이 필요합니다',
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
