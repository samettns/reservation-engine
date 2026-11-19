// Load test for POST /events/{id}/reserve.
//
// Two modes (env MODE):
//   contention : stock never runs out; VUS users reserve in a loop for DURATION.
//                Measures write throughput on ONE hot row -> the difference between strategies.
//   sellout    : a flash sale; ITERATIONS requests shared by VUS users against a small stock.
//                Most requests are rejected -> measures the cost of saying "sold out".
//
// Every request uses a fresh (user, Idempotency-Key) pair, so idempotent replays never
// short-circuit the work being measured.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';
const EVENT_ID = __ENV.EVENT_ID;
const RUN_ID = __ENV.RUN_ID || 'run';
const MODE = __ENV.MODE || 'contention';
const VUS = parseInt(__ENV.VUS || '10', 10);

const created = new Counter('status_201');
const soldOut = new Counter('status_409');
const contended = new Counter('status_503');
const other = new Counter('status_other');
const createdLatency = new Trend('latency_201', true);

function scenario() {
  if (MODE === 'sellout') {
    return {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: parseInt(__ENV.ITERATIONS || '20000', 10),
      maxDuration: '5m',
    };
  }
  return { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '20s' };
}

export const options = {
  discardResponseBodies: true,
  scenarios: { reserve: scenario() },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 409 (sold out) is a valid business answer, not a failed request.
http.setResponseCallback(http.expectedStatuses(201, 409));

const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
  const id = `${__VU}-${__ITER}`;
  const res = http.post(
    `${BASE_URL}/events/${EVENT_ID}/reserve`,
    JSON.stringify({ userId: `u-${id}` }),
    { headers: { ...HEADERS, 'Idempotency-Key': `${RUN_ID}-${id}` }, tags: { name: 'reserve' } },
  );
  switch (res.status) {
    case 201:
      created.add(1);
      createdLatency.add(res.timings.duration);
      break;
    case 409:
      soldOut.add(1);
      break;
    case 503:
      contended.add(1);
      break;
    default:
      other.add(1);
  }
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE || '/results/summary.json']: JSON.stringify(data) };
}
