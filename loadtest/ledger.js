// k6 load test for the ledger service. Three scenarios, one after another:
//
//   spread  32 VUs, 60 s  transfers between 64 accounts: little lock contention
//   hot     32 VUs, 30 s  every VU pays into one merchant account: all writers queue on one row lock
//   holds   16 VUs, 30 s  authorise a hold, then the merchant captures part of it (2 requests per iteration)
//
// Run everything on one Docker network (see README, "Load test"):
//   docker compose --profile app up -d --build
//   docker run --rm --network ledger-service_default -v "$PWD/loadtest:/scripts" \
//     -e BASE_URL=http://app:8080 -e ADMIN_PASSWORD=... grafana/k6 run /scripts/ledger.js
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'load-admin-password';
const ACCOUNTS = 64;
const PASSWORD = 'load-test-password';
const RUN = Date.now().toString(36); // keeps idempotency keys and usernames unique across runs

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios: {
    spread: { executor: 'constant-vus', exec: 'spread', vus: 32, duration: '60s' },
    hot: { executor: 'constant-vus', exec: 'hot', vus: 32, duration: '30s', startTime: '65s' },
    holds: { executor: 'constant-vus', exec: 'holds', vus: 16, duration: '30s', startTime: '100s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    // Listed so the summary reports each scenario on its own.
    'http_req_duration{scenario:spread}': ['p(95)<500'],
    'http_req_duration{scenario:hot}': ['p(95)<1000'],
    'http_req_duration{scenario:holds}': ['p(95)<1000'],
    'http_reqs{scenario:spread}': ['count>0'],
    'http_reqs{scenario:hot}': ['count>0'],
    'http_reqs{scenario:holds}': ['count>0'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(path, body, token, key) {
  const headers = Object.assign({}, JSON_HEADERS);
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  return http.post(`${BASE}${path}`, body === null ? null : JSON.stringify(body), { headers });
}

function token(username, password) {
  const r = post('/api/v1/auth/token', { username, password });
  if (r.status !== 200) fail(`login ${username}: ${r.status} ${r.body}`);
  return r.json('accessToken');
}

// Users, accounts and money, created once. Not part of the measured numbers.
export function setup() {
  const admin = token('admin', ADMIN_PASSWORD);
  const users = [];
  for (let i = 0; i < ACCOUNTS + 1; i++) {
    const username = `load-${RUN}-${i}`;
    const reg = post('/api/v1/auth/register', { username, password: PASSWORD });
    if (reg.status !== 201) fail(`register: ${reg.status} ${reg.body}`);
    const t = token(username, PASSWORD);
    const acc = post('/api/v1/accounts', { currency: 'IDR' }, t);
    if (acc.status !== 201) fail(`open account: ${acc.status} ${acc.body}`);
    const dep = post('/api/v1/deposits', { accountId: acc.json('id'), amount: 1000000000 }, admin, `seed-${RUN}-${i}`);
    if (dep.status !== 201) fail(`deposit: ${dep.status} ${dep.body}`);
    users.push({ token: t, account: acc.json('id') });
  }
  // The last user is the merchant of the hot and holds scenarios.
  return { users: users.slice(0, ACCOUNTS), merchant: users[ACCOUNTS] };
}

function myIndex(data) {
  return (exec.vu.idInTest - 1) % data.users.length;
}

function me(data) {
  return data.users[myIndex(data)];
}

function key(prefix) {
  return `${prefix}-${RUN}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;
}

// Prints the first few unexpected answers, so a failure in the summary can be explained.
let reported = 0;
function expect(r, status, name) {
  const ok = check(r, { [name]: (x) => x.status === status });
  if (!ok && reported < 5) {
    reported++;
    console.warn(`${name}: got ${r.status} ${r.error || ''} ${String(r.body).slice(0, 200)}`);
  }
  return ok;
}

function amount() {
  return 1 + Math.floor(Math.random() * 1000);
}

export function spread(data) {
  // Indices, not object identity: setup data reaches the VUs as copies, so indexOf() would not find them.
  const i = myIndex(data);
  const from = data.users[i];
  let j = Math.floor(Math.random() * data.users.length);
  if (j === i) j = (j + 1) % data.users.length;
  const to = data.users[j];
  const r = post('/api/v1/transfers',
    { fromAccountId: from.account, toAccountId: to.account, amount: amount(), currency: 'IDR' },
    from.token, key('s'));
  expect(r, 201, 'transfer 201');
}

export function hot(data) {
  const from = me(data);
  const r = post('/api/v1/transfers',
    { fromAccountId: from.account, toAccountId: data.merchant.account, amount: amount(), currency: 'IDR' },
    from.token, key('h'));
  expect(r, 201, 'transfer 201');
}

export function holds(data) {
  const from = me(data);
  const held = amount() + 100;
  const a = post('/api/v1/holds',
    { fromAccountId: from.account, toAccountId: data.merchant.account, amount: held, currency: 'IDR' },
    from.token, key('a'));
  if (!expect(a, 201, 'hold 201')) return;
  const c = post(`/api/v1/holds/${a.json('id')}/capture`, { amount: held - 50 }, data.merchant.token);
  expect(c, 200, 'capture 200');
}

export function handleSummary(data) {
  const m = data.metrics;
  const row = (name) => {
    const d = m[`http_req_duration{scenario:${name}}`];
    const n = m[`http_reqs{scenario:${name}}`];
    if (!d || !n) return `${name}: no data`;
    const v = d.values;
    const seconds = { spread: 60, hot: 30, holds: 30 }[name];
    return `${name.padEnd(7)} requests ${String(n.values.count).padStart(6)}  ` +
      `${(n.values.count / seconds).toFixed(0).padStart(5)} req/s  ` +
      `p50 ${v.med.toFixed(1)} ms  p95 ${v['p(95)'].toFixed(1)} ms  p99 ${(v['p(99)'] || 0).toFixed(1)} ms  ` +
      `max ${v.max.toFixed(0)} ms`;
  };
  const lines = [
    '',
    'ledger load test',
    row('spread'),
    row('hot'),
    row('holds'),
    `failed requests ${(m.http_req_failed.values.rate * 100).toFixed(2)} %   checks ${(m.checks.values.rate * 100).toFixed(2)} %`,
    '',
  ];
  return { stdout: lines.join('\n'), '/scripts/last-summary.json': JSON.stringify(data, null, 2) };
}
