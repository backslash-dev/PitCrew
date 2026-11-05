import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';

describe('loadConfig', () => {
  it('값이 없으면 로컬 기본값을 쓴다', () => {
    expect(loadConfig({})).toMatchObject({ HOST: '127.0.0.1', PORT: 3000, DATABASE_URL: expect.stringContaining(':3308/pitcrew') });
  });

  it('잘못된 포트는 거부한다', () => {
    expect(() => loadConfig({ PORT: '70000' })).toThrow(/PORT/);
  });

  it('빈 API_TOKEN은 없는 것으로 본다', () => {
    expect(loadConfig({ API_TOKEN: '' }).API_TOKEN).toBeUndefined();
  });

  it('운영 환경은 API_TOKEN이 없으면 뜨지 않는다', () => {
    expect(() => loadConfig({ NODE_ENV: 'production' })).toThrow(/API_TOKEN/);
    expect(loadConfig({ NODE_ENV: 'production', API_TOKEN: 'x'.repeat(32) }).API_TOKEN).toHaveLength(32);
  });
});
