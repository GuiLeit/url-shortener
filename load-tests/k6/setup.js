import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const SEED_COUNT = parseInt(__ENV.SEED_COUNT || '200');

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
    if (res.status === 201 && res.body) {
      try {
        const sc = JSON.parse(res.body).shortCode;
        if (sc) codes.push(sc);
      } catch (_) {
        console.warn(`Seed ${i}: could not parse response body`);
      }
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

// k6 passes setup()'s return value as data.setup (since k6 v0.38).
export function handleSummary(data) {
  const codes = (data && data.setup) ? data.setup : [];
  return {
    '/scripts/shortcodes.json': JSON.stringify(codes),
    stdout: `\nWrote ${codes.length} shortcodes → /scripts/shortcodes.json\n`,
  };
}
