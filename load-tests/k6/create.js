import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m',  target: 20 },
    { duration: '15s', target: 0  },
  ],
  thresholds: {
    // Latency for successful creates only
    'http_req_duration{status:201}': ['p(99)<300'],
    // Allow up to 5% failures — nginx rate-limits creates to 10 r/s per source IP
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  const vu   = exec.vu.idInTest;
  const iter = exec.vu.iterationInScenario;
  const payload = JSON.stringify({
    url: `https://example.com/load-test/vu${vu}/iter${iter}`,
  });

  const res = http.post(`${BASE_URL}/api/v1/urls`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'created 201': (r) => r.status === 201,
    'rate-limited 429': (r) => r.status === 429,
  });
}
