import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

const codes = new SharedArray('codes', () =>
  JSON.parse(open('./shortcodes.json'))
);

export const options = {
  stages: [
    { duration: '1m',  target: 50  },
    { duration: '3m',  target: 50  },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(99)<100'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  check(res, { 'is 302': (r) => r.status === 302 });
}
