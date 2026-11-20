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
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';
const EVENT_ID = __ENV.EVENT_ID;
const RUN_ID = __ENV.RUN_ID || 'run';
const MODE = __ENV.MODE || 'contention';
const VUS = parseInt(__ENV.VUS || '10', 10);

const created = new Counter('status_201');
const soldOut = new Counter('status_409');
const contended = new Counter('status_503');
const serverError = new Counter('status_500');
const other = new Counter('status_other'); // anything else: 502/504 from nginx, timeouts (status 0)...
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

// RETRY_503=1: a well-behaved client. On 503 it waits Retry-After seconds and retries the SAME
// request (same Idempotency-Key), up to MAX_ATTEMPTS times. Without it, a closed-model VU fires
// its next request the moment it is refused, which turns fast refusals into a refusal storm.
const RETRY_503 = __ENV.RETRY_503 === '1';
const MAX_ATTEMPTS = parseInt(__ENV.MAX_ATTEMPTS || '10', 10);
const createdEndToEnd = new Trend('latency_201_e2e', true); // first attempt -> 201, waits included
const gaveUp = new Counter('gave_up_after_503');

export default function () {
  const id = `${__VU}-${__ITER}`;
  const started = Date.now();
  let res;
  for (let attempt = 1; ; attempt++) {
    res = http.post(
      `${BASE_URL}/events/${EVENT_ID}/reserve`,
      JSON.stringify({ userId: `u-${id}` }),
      { headers: { ...HEADERS, 'Idempotency-Key': `${RUN_ID}-${id}` }, tags: { name: 'reserve' } },
    );
    if (!RETRY_503 || res.status !== 503) {
      break;
    }
    contended.add(1); // every refused attempt is counted
    if (attempt === MAX_ATTEMPTS) {
      gaveUp.add(1);
      return;
    }
    sleep(parseFloat(res.headers['Retry-After'] || '1'));
  }
  if (res.status === 201) {
    createdEndToEnd.add(Date.now() - started);
  }
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
    case 500:
      serverError.add(1);
      break;
    default:
      other.add(1);
  }
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_FILE || '/results/summary.json']: JSON.stringify(data) };
}
