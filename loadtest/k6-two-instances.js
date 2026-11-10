// HTTP-level check that one client's limit holds across two app instances.
//   docker compose up --build -d
//   k6 run loadtest/k6-two-instances.js
// Expect roughly: 20 allowed in the first instant (burst), then ~10/s, regardless of
// which instance served the request. Anything above that means the limit leaked.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const allowed = new Counter('allowed');
const limited = new Counter('limited');

export const options = {
  scenarios: {
    burst: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '10s', preAllocatedVUs: 50 },
  },
};

export default function () {
  const host = __ITER % 2 === 0 ? 'http://localhost:8081' : 'http://localhost:8082';
  const res = http.get(`${host}/api/hello`, { headers: { 'X-Api-Key': 'k6-client' } });
  check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });
  if (res.status === 200) allowed.add(1);
  if (res.status === 429) limited.add(1);
}
