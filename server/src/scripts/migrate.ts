import { loadConfig } from '../config.js';
import { migrate } from '../db/migrate.js';

const applied = await migrate(loadConfig().DATABASE_URL, (message) => console.log(message));
console.log(applied.length === 0 ? 'no pending migrations' : `${applied.length} migration(s) applied`);
