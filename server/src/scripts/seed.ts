import { loadConfig } from '../config.js';
import { migrate } from '../db/migrate.js';
import { createPool } from '../db/pool.js';

/** 시연용 일행·예약을 넣는다. 예약 시각은 지금 기준이라 언제 실행해도 다가오는 예약이 된다. 운영에서는 실행하지 않는다. */
const config = loadConfig();
if (config.NODE_ENV === 'production') throw new Error('운영 환경에서는 시드를 넣지 않습니다');

await migrate(config.DATABASE_URL);
const db = createPool(config.DATABASE_URL);
try {
  const contacts = [
    ['5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b01', '김민수', '010-2481-3307'],
    ['5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b02', '이서연', '010-7716-0452'],
    ['5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b03', '박준호', '010-5093-8821'],
  ] as const;
  for (const [id, name, phone] of contacts) {
    await db.execute('INSERT INTO contacts (id, name, phone) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone)', [id, name, phone]);
  }
  const dinner = new Date(Math.ceil((Date.now() + 50 * 60_000) / (30 * 60_000)) * 30 * 60_000);
  const reservations = [
    ['8a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c01', '온담 한식당', dinner, 3, [contacts[0][0], contacts[1][0]]],
    ['8a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c02', '세모 커피 판교점', new Date(dinner.getTime() + 2 * 3_600_000), 2, [contacts[2][0]]],
  ] as const;
  for (const [id, place, at, party, companions] of reservations) {
    await db.execute(
      'INSERT INTO reservations (id, place_name, reserved_at, party_size) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE place_name = VALUES(place_name), reserved_at = VALUES(reserved_at), party_size = VALUES(party_size)',
      [id, place, at, party],
    );
    await db.execute('DELETE FROM reservation_companions WHERE reservation_id = ?', [id]);
    for (const contactId of companions) {
      await db.execute('INSERT INTO reservation_companions (reservation_id, contact_id) VALUES (?, ?)', [id, contactId]);
    }
  }
  console.log(`seeded ${contacts.length} contacts, ${reservations.length} reservations (dinner at ${dinner.toISOString()})`);
} finally {
  await db.end();
}
