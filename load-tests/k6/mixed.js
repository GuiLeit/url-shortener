import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const codes = new SharedArray('codes', () =>
  JSON.parse(open('./shortcodes.json'))
);

export const options = {
  scenarios: {
    reads: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s', target: 200 },
        { duration: '3m',  target: 200 },
        { duration: '30s', target: 0   },
      ],
      exec: 'doRead',
    },
    writes: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s', target: 10 },
        { duration: '3m',  target: 10 },
        { duration: '30s', target: 0  },
      ],
      exec: 'doWrite',
    },
  },
  thresholds: {
    'http_req_duration{scenario:reads}':  ['p(99)<100'],
    'http_req_duration{scenario:writes}': ['p(99)<300'],
    http_req_failed: ['rate<0.05'],
  },
};

export function doRead() {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  check(res, { 'redirect 302': (r) => r.status === 302 });
}

export function doWrite() {
  const vu   = exec.vu.idInTest;
  const iter = exec.vu.iterationInScenario;
  const res  = http.post(
    `${BASE_URL}/api/v1/urls`,
    JSON.stringify({ url: `https://example.com/mixed/vu${vu}/iter${iter}` }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, {
    'created 201':      (r) => r.status === 201,
    'rate-limited 429': (r) => r.status === 429,
  });
}

// Required default export (never called — scenarios use exec functions)
export default function () {}
