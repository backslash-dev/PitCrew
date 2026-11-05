import { defineConfig } from 'vitest/config';

// 테스트가 같은 MySQL 테스트 DB를 비우고 쓰므로 파일을 순서대로 돌린다
export default defineConfig({
  test: {
    fileParallelism: false,
    hookTimeout: 30_000,
  },
});
