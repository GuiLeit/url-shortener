import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const SEED_COUNT = parseInt(__ENV.SEED_COUNT || '200');

// Module-level — written by teardown(), read by handleSummary().
// Both run in the k6 main goroutine (same JS VM), so the assignment is visible.
let captured = [];

export const options = {
  vus: 1,
  iterations: 1,
};

export function setup() {
  const codes = [];
  for (let i = 0; i < SEED_COUNT; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ url: `https://example.com/seed/${i}` }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status === 201) {
      codes.push(JSON.parse(res.body).shortCode);
    } else {
      console.warn(`Seed ${i}: unexpected status ${res.status}`);
    }
    sleep(0.15);
  }
  console.log(`Seeded ${codes.length}/${SEED_COUNT} URLs`);
  return codes;
}

// No-op default: all work is in setup().
export default function () {}

export function teardown(data) {
  captured = data;
}

export function handleSummary() {
  return {
    '/scripts/shortcodes.json': JSON.stringify(captured),
    stdout: `\nWrote ${captured.length} shortcodes → /scripts/shortcodes.json\n`,
  };
}
