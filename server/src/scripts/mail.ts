import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import { loadConfig } from '../config.js';
import { migrate } from '../db/migrate.js';
import { createPool } from '../db/pool.js';
import { DEMO_MAILS } from './demoMails.js';

const config = loadConfig();
if (config.NODE_ENV === 'production') throw new Error('운영 환경에서는 시연 메일을 넣지 않습니다');

await migrate(config.DATABASE_URL);
const db = createPool(config.DATABASE_URL);
try {
  const [[row]] = await db.query<RowDataPacket[]>('SELECT COUNT(*) AS total FROM mails');
  const mail = DEMO_MAILS[Number(row?.total ?? 0) % DEMO_MAILS.length]!;
  const id = randomUUID();
  await db.execute('INSERT INTO mails (id, sender_name, sender_email, subject, body, received_at) VALUES (?, ?, ?, ?, ?, ?)', [
    id,
    mail.senderName,
    mail.senderEmail,
    mail.subject,
    mail.body,
    new Date(),
  ]);
  console.log(id);
} finally {
  await db.end();
}
