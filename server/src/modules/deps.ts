import type { Pool } from 'mysql2/promise';
import type { Gateways } from '../adapters/gateways.js';

export interface Deps {
  db: Pool;
  gateways: Gateways;
}
