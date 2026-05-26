import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const SEED_COUNT = parseInt(__ENV.SEED_COUNT || '300', 10);

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
        const sc = JSON.parse(res.body).short_code;
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

// k6 passes setup()'s return value as data.setup_data in handleSummary.
export function handleSummary(data) {
  // /scripts/ maps to load-tests/k6/ via Docker volume mount (-v $PWD/load-tests/k6:/scripts)
  const codes = data.setup_data || [];
  if (codes.length === 0) {
    console.error('No shortcodes collected — all seed requests may have failed');
  }
  return {
    '/scripts/shortcodes.json': JSON.stringify(codes),
    stdout: `\nWrote ${codes.length} shortcodes → /scripts/shortcodes.json\n`,
  };
}
