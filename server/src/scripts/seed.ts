import { loadConfig } from '../config.js';
import { migrate } from '../db/migrate.js';
import { createPool } from '../db/pool.js';
import { DEMO_MAILS } from './demoMails.js';

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
  const freeFrom = new Date(Math.ceil(Date.now() / (10 * 60_000)) * 10 * 60_000);
  const hours = (n: number) => new Date(freeFrom.getTime() + n * 3_600_000);
  const profiles = [
    [contacts[0][0], 1, 37.3948, 127.1112, freeFrom, hours(6)],
    [contacts[1][0], 0, 37.5563, 126.9236, freeFrom, hours(6)],
    [contacts[2][0], 1, 37.5133, 127.1001, hours(1), hours(7)],
  ] as const;
  for (const [contactId, hasAgent, latitude, longitude, from, until] of profiles) {
    await db.execute(
      `INSERT INTO peer_profiles (contact_id, has_agent, latitude, longitude, free_from, free_until) VALUES (?, ?, ?, ?, ?, ?)
       ON DUPLICATE KEY UPDATE has_agent = VALUES(has_agent), latitude = VALUES(latitude), longitude = VALUES(longitude), free_from = VALUES(free_from), free_until = VALUES(free_until)`,
      [contactId, hasAgent, latitude, longitude, from, until],
    );
  }
  // 앞선 시연의 약속 예약이 남으면 일정이 겹쳐 새 약속을 못 잡는다
  await db.execute('DELETE r FROM reservations r JOIN meetups m ON m.reservation_id = r.id');
  await db.execute('DELETE FROM meetups');
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
  const routineOrders = [1, 2, 3].map((daysAgo, index) => [
    `3c7e9a1b-5d2f-4b8e-a6c4-0f1e2d3c4b0${index + 1}`,
    new Date(Date.now() - daysAgo * 86_400_000 + 10 * 60_000),
  ] as const);
  for (const [id, pickupAt] of routineOrders) {
    const placedAt = new Date(pickupAt.getTime() - 15 * 60_000);
    await db.execute(
      `INSERT INTO orders (id, store_id, item, pickup_at, status, provider, created_at) VALUES (?, 's-semo-yeoksam', '아메리카노', ?, 'ACCEPTED', 'mock', ?)
       ON DUPLICATE KEY UPDATE pickup_at = VALUES(pickup_at), created_at = VALUES(created_at), status = 'ACCEPTED'`,
      [id, pickupAt, placedAt],
    );
  }
  await db.execute('DELETE FROM mails');
  const mail = DEMO_MAILS[0];
  await db.execute('INSERT INTO mails (id, sender_name, sender_email, subject, body, received_at) VALUES (?, ?, ?, ?, ?, ?)', [
    '6b2e4d8f-1a3c-4e5b-9d7f-2c4e6a8b0d01',
    mail.senderName,
    mail.senderEmail,
    mail.subject,
    mail.body,
    new Date(Date.now() - 4 * 60_000),
  ]);
  console.log(
    `seeded ${contacts.length} contacts (${profiles.length} peer profiles), ${reservations.length} reservations (dinner at ${dinner.toISOString()}), ${routineOrders.length} routine orders, 1 mail`,
  );
} finally {
  await db.end();
}
