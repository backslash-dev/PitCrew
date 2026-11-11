import mysql, { type Pool } from 'mysql2/promise';

/** 세션 시간대를 UTC로 고정해 NOW()와 앱 값이 어긋나지 않게 한다. */
export function createPool(url: string): Pool {
  const pool = mysql.createPool({
    uri: url,
    timezone: 'Z',
    decimalNumbers: true,
    connectionLimit: 10,
    waitForConnections: true,
  });
  pool.pool.on('connection', (connection) => {
    connection.query("SET time_zone = '+00:00'");
  });
  return pool;
}
