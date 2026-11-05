import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';

describe('loadConfig', () => {
  it('값이 없으면 기본값을 쓴다', () => {
    expect(loadConfig({})).toMatchObject({ HOST: '127.0.0.1', PORT: 3000, LOG_LEVEL: 'info' });
  });

  it('잘못된 포트는 거부한다', () => {
    expect(() => loadConfig({ PORT: '70000' })).toThrow(/PORT/);
  });
});
