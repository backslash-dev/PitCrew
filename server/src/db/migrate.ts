import { readdir, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import mysql, { type RowDataPacket } from 'mysql2/promise';

const MIGRATIONS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'migrations');
const LOCK_NAME = 'pitcrew_migrate';

/** MySQL DDL은 트랜잭션이 안 돼 파일 단위로 기록한다. */
export async function migrate(url: string, log: (message: string) => void = () => {}): Promise<string[]> {
  const connection = await mysql.createConnection({ uri: url, multipleStatements: true, timezone: 'Z' });
  try {
    const [[lock]] = await connection.query<RowDataPacket[]>('SELECT GET_LOCK(?, 30) AS acquired', [LOCK_NAME]);
    if (lock?.acquired !== 1) throw new Error('마이그레이션 잠금을 얻지 못했습니다');
    try {
      await connection.query(
        'CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(100) NOT NULL PRIMARY KEY, applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))',
      );
      const [rows] = await connection.query<RowDataPacket[]>('SELECT version FROM schema_migrations');
      const applied = new Set(rows.map((row) => String(row.version)));
      const files = (await readdir(MIGRATIONS_DIR)).filter((f) => /^\d{3}_.+\.sql$/.test(f)).sort();
      const newlyApplied: string[] = [];
      for (const file of files) {
        if (applied.has(file)) continue;
        const sql = await readFile(join(MIGRATIONS_DIR, file), 'utf8');
        await connection.query(sql);
        await connection.query('INSERT INTO schema_migrations (version) VALUES (?)', [file]);
        newlyApplied.push(file);
        log(`migration applied: ${file}`);
      }
      return newlyApplied;
    } finally {
      await connection.query('SELECT RELEASE_LOCK(?)', [LOCK_NAME]);
    }
  } finally {
    await connection.end();
  }
}
