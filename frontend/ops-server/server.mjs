// 시연 전용 운영 조회 서버 (읽기 전용). 운영 배포 금지, 머지 전 팀 합의 필요.
// 백엔드에 원장, Outbox, 처리 기록, 정합성을 조회하는 정식 API가 없어서 시연 때 DB와 Kafka를 직접 읽는다.
//
// 안전장치
// - DB 세션은 읽기 전용(default_transaction_read_only)이라 쓰기가 불가능하다
// - 요청마다 Gateway와 같은 방식(HS256, JWT_SECRET)으로 토큰을 검증하고, 계좌, 청약 조회는 본인 것만 돌려준다
// - 127.0.0.1에만 열린다. 비밀값(DB 비밀번호, JWT 키)은 출력하지 않는다
//
// 설정: 저장소 루트 .env(DB_PASSWORD, JWT_SECRET 등)를 읽고, 환경변수가 있으면 그 값을 우선한다
//   OPS_PORT(8099) OPS_DB_HOST(localhost) OPS_DB_PORT(5432) OPS_DB_NAME(ipo) OPS_DB_USER(postgres) OPS_KAFKA(localhost:29092)

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';
import { Kafka, logLevel } from 'kafkajs';

const here = path.dirname(fileURLToPath(import.meta.url));
const rootEnv = readDotEnv(path.resolve(here, '../../.env'));
const env = (k, d) => process.env[k] ?? rootEnv[k] ?? d;

const PORT = Number(env('OPS_PORT', 8099));
const JWT_SECRET = env('JWT_SECRET');
if (!JWT_SECRET) {
  console.error('JWT_SECRET이 없습니다 (.env 또는 환경변수). 토큰을 검증할 수 없어 시작하지 않습니다');
  process.exit(1);
}
const jwtKey = Buffer.from(JWT_SECRET.trim(), 'base64');

const pool = new pg.Pool({
  host: env('OPS_DB_HOST', 'localhost'),
  port: Number(env('OPS_DB_PORT', 5432)),
  database: env('OPS_DB_NAME', env('DB_NAME', 'ipo')),
  user: env('OPS_DB_USER', env('DB_USERNAME', 'postgres')),
  password: env('OPS_DB_PASSWORD', env('DB_PASSWORD')),
  max: 4,
  options: '-c default_transaction_read_only=on',
});

// Kafka가 꺼져 있을 때 기본 재시도(5회)로 화면이 오래 멈추지 않게 짧게 둔다
const kafka = new Kafka({
  clientId: 'ops-demo',
  brokers: env('OPS_KAFKA', 'localhost:29092').split(','),
  logLevel: logLevel.NOTHING,
  connectionTimeout: 2000,
  retry: { retries: 1 },
});

function readDotEnv(file) {
  const out = {};
  try {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
      if (m) out[m[1]] = m[2].replace(/^['"]|['"]$/g, '');
    }
  } catch {
    /* .env가 없으면 환경변수만 쓴다 */
  }
  return out;
}

// Gateway와 같은 검증: HS256 서명, 만료, iss=account-service, sub
function verifyJwt(header) {
  const token = header?.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const expected = crypto.createHmac('sha256', jwtKey).update(`${parts[0]}.${parts[1]}`).digest('base64url');
  const a = Buffer.from(expected);
  const b = Buffer.from(parts[2]);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const head = JSON.parse(Buffer.from(parts[0], 'base64url').toString());
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
    if (head.alg !== 'HS256' || claims.iss !== 'account-service' || !claims.sub || !claims.exp) return null;
    if (claims.exp * 1000 < Date.now()) return null;
    return claims;
  } catch {
    return null;
  }
}

const UUID = /^[0-9a-fA-F-]{36}$/;
const q = async (sql, params = []) => (await pool.query(sql, params)).rows;

async function ledger(accountId, userId) {
  const owner = await q('select user_id from account.account where account_id = $1', [accountId]);
  if (owner.length === 0 || owner[0].user_id !== userId) return [403, { message: '본인 계좌만 볼 수 있어요' }];
  const rows = await q(
    `select type, amount, cash_balance_after, margin_balance_after, subscription_id, created_at
       from account.ledger_entry where account_id = $1 order by created_at desc, ledger_entry_id desc limit 50`,
    [accountId],
  );
  return [200, rows.map((r) => ({ type: r.type, amount: Number(r.amount), cashBalanceAfter: Number(r.cash_balance_after), marginBalanceAfter: Number(r.margin_balance_after), subscriptionId: r.subscription_id, createdAt: r.created_at }))];
}

async function trace(subscriptionId, userId) {
  const sub = await q(
    `select subscription_id, user_id, ipo_id, account_id, status, reject_reason, cancel_reason, requested_quantity, margin_amount, allocated_amount, refund_amount, created_at
       from subscription.subscription where subscription_id = $1`,
    [subscriptionId],
  );
  if (sub.length === 0) return [404, { message: '청약이 아직 저장되지 않았거나 없어요' }];
  if (sub[0].user_id !== userId) return [403, { message: '본인 청약만 볼 수 있어요' }];
  const [margin] = await q('select margin_id, status, amount, posted_amount from account.margin where subscription_id = $1', [subscriptionId]);
  const [allocation] = await q(
    'select equal_allocated_quantity, proportional_allocated_quantity, allocated_quantity, allocated_amount from allocation.allocation where subscription_id = $1',
    [subscriptionId],
  );
  const led = await q(
    `select type, amount, cash_balance_after, margin_balance_after, subscription_id, created_at
       from account.ledger_entry where subscription_id = $1 order by created_at, ledger_entry_id`,
    [subscriptionId],
  );
  // 세 서비스 Outbox에서 이 청약의 이벤트. 이벤트 ID = outbox_id (Outbox 행으로 메시지를 만들 때 그대로 씀)
  const events = await q(
    `select 'account' as service, outbox_id, topic, message_key, created_at, published_at, payload from account.outbox where payload->>'subscriptionId' = $1
     union all
     select 'subscription', outbox_id, topic, message_key, created_at, published_at, payload from subscription.outbox where payload->>'subscriptionId' = $1
     union all
     select 'allocation', outbox_id, topic, message_key, created_at, published_at, payload from allocation.outbox where payload->>'subscriptionId' = $1`,
    [subscriptionId],
  );
  const ids = events.map((e) => e.outbox_id);
  const consumed = ids.length
    ? await q(
        `select event_id, consumer_name, processed_at from account.processed_event where event_id = any($1::uuid[])
         union all select event_id, consumer_name, processed_at from subscription.processed_event where event_id = any($1::uuid[])
         union all select event_id, consumer_name, processed_at from allocation.processed_event where event_id = any($1::uuid[])`,
        [ids],
      )
    : [];
  const s = sub[0];
  delete s.user_id;
  return [
    200,
    {
      subscription: s,
      margin: margin ?? null,
      allocation: allocation ?? null,
      ledger: led.map((r) => ({ type: r.type, amount: Number(r.amount), cashBalanceAfter: Number(r.cash_balance_after), marginBalanceAfter: Number(r.margin_balance_after), subscriptionId: r.subscription_id, createdAt: r.created_at })),
      events: events.map((e) => ({
        service: e.service,
        topic: e.topic,
        messageKey: e.message_key,
        eventId: e.outbox_id,
        createdAt: e.created_at,
        publishedAt: e.published_at,
        payload: e.payload,
        consumedBy: consumed.filter((c) => c.event_id === e.outbox_id).map((c) => ({ consumer: c.consumer_name, processedAt: c.processed_at })),
      })),
      checkedAt: new Date().toISOString(),
    },
  ];
}

// loadtest/sql/verify.sql의 전체 계좌 기준 검사를 그대로 쓴다 (위반 행 수, 0이면 정상)
const CHECKS = [
  {
    id: 'margin-balance',
    name: '계좌 청약증거금 = 인출 중인 증거금 합',
    description: 'verify.sql 2번. 계좌의 청약증거금 잔고가 PENDING 증거금 금액 합과 같은지',
    sql: `select count(*) from (select a.account_id from account.account a left join account.margin m on m.account_id = a.account_id and m.status = 'PENDING'
          group by a.account_id, a.margin_balance having a.margin_balance <> coalesce(sum(m.amount), 0)) x`,
  },
  {
    id: 'ledger-balance',
    name: '계좌 잔고 = 마지막 원장의 거래 후 잔고',
    description: 'verify.sql 3번. 잔고가 원장 기록과 어긋난 계좌가 없는지',
    sql: `with last as (select distinct on (account_id) account_id, cash_balance_after, margin_balance_after from account.ledger_entry order by account_id, created_at desc, ledger_entry_id desc)
          select count(*) from account.account a join last l using (account_id) where a.cash_balance <> l.cash_balance_after or a.margin_balance <> l.margin_balance_after`,
  },
  {
    id: 'single-reserve',
    name: '증거금마다 인출 원장 정확히 1줄',
    description: 'verify.sql 11번 (전체). 중복 인출, 원장 누락이 없는지',
    sql: `select count(*) from (select m.margin_id from account.margin m left join account.ledger_entry l on l.margin_id = m.margin_id and l.type = 'MARGIN_PENDING'
          group by m.margin_id, m.amount having count(l.ledger_entry_id) <> 1 or coalesce(sum(l.amount), 0) <> m.amount) x`,
  },
  {
    id: 'settle-ledger',
    name: '정산, 반환 금액 = 원장',
    description: 'verify.sql 12번 (전체). POSTED는 차감 + 환불 = 증거금, VOIDED는 반환 = 증거금',
    sql: `with l as (select margin_id, coalesce(sum(amount) filter (where type = 'MARGIN_POST'), 0) as post, coalesce(sum(amount) filter (where type = 'REFUND'), 0) as refund,
          coalesce(sum(amount) filter (where type = 'MARGIN_VOID'), 0) as void from account.ledger_entry where margin_id is not null group by margin_id)
          select count(*) from account.margin m left join l using (margin_id) where not case m.status
            when 'PENDING' then coalesce(l.post, 0) + coalesce(l.refund, 0) + coalesce(l.void, 0) = 0
            when 'POSTED' then coalesce(l.post, 0) = m.posted_amount and coalesce(l.refund, 0) = m.amount - m.posted_amount and coalesce(l.void, 0) = 0
            when 'VOIDED' then coalesce(l.void, 0) = m.amount and coalesce(l.post, 0) + coalesce(l.refund, 0) = 0 else false end`,
  },
  {
    id: 'negative',
    name: '음수 잔고 없음',
    description: '예수금, 청약증거금이 0보다 작은 계좌가 없는지',
    sql: 'select count(*) from account.account where cash_balance < 0 or margin_balance < 0',
  },
];

// DLT 토픽별 메시지 수. 실패해도 예외 대신 { dlt: null, dltError }로 돌려준다
async function dltCounts() {
  const admin = kafka.admin();
  try {
    await admin.connect();
    const topics = (await admin.listTopics()).filter((t) => t.endsWith('.DLT')).sort();
    const dlt = [];
    for (const t of topics) {
      const offs = await admin.fetchTopicOffsets(t);
      dlt.push({ topic: t, messages: offs.reduce((a, o) => a + (Number(o.high) - Number(o.low)), 0) });
    }
    return { dlt };
  } catch (e) {
    return { dlt: null, dltError: `Kafka 조회 실패: ${e.message}` };
  } finally {
    await admin.disconnect().catch(() => {});
  }
}

async function summary() {
  // Kafka 조회가 늦어도 DB 결과가 같이 늦어지지 않게 동시에 시작한다
  const kafkaPart = dltCounts();
  const outbox = await q(`select 'account' as service, count(*) filter (where published_at is null) as c from account.outbox
     union all select 'subscription', count(*) filter (where published_at is null) from subscription.outbox
     union all select 'allocation', count(*) filter (where published_at is null) from allocation.outbox`);
  const checks = [];
  for (const c of CHECKS) {
    try {
      const [row] = await q(c.sql);
      checks.push({ id: c.id, name: c.name, description: c.description, violations: Number(row.count) });
    } catch (e) {
      checks.push({ id: c.id, name: c.name, description: c.description, violations: null, error: String(e.message) });
    }
  }
  const { dlt, dltError } = await kafkaPart;
  return [200, { outboxUnpublished: outbox.map((o) => ({ service: o.service, count: Number(o.c) })), dlt, dltError, checks, checkedAt: new Date().toISOString() }];
}

// DLT 메시지 내용 (가장 최근 limit건). Spring이 붙인 실패 정보 헤더(원래 토픽, 예외 메시지 등)도 같이 돌려준다
function decodeHeader(buf) {
  if (!buf) return null;
  const b = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  const text = b.toString('utf8');
  // 숫자 헤더(원래 파티션 4바이트, 오프셋, 타임스탬프 8바이트)는 이진값이라 숫자로 읽는다
  if (/[\x00-\x08]/.test(text)) {
    if (b.length === 4) return String(b.readInt32BE(0));
    if (b.length === 8) return String(b.readBigInt64BE(0));
  }
  return text;
}

async function dltMessages(topic, limit) {
  if (!/^[a-z0-9.\-]+\.DLT$/.test(topic)) return [400, { message: 'DLT 토픽 이름이 아니에요' }];
  const admin = kafka.admin();
  await admin.connect();
  let offsets;
  try {
    offsets = await admin.fetchTopicOffsets(topic);
  } finally {
    await admin.disconnect().catch(() => {});
  }
  const total = offsets.reduce((a, o) => a + (Number(o.high) - Number(o.low)), 0);
  if (total === 0) return [200, { topic, total: 0, messages: [] }];
  const groupId = `ops-dlt-${crypto.randomUUID()}`;
  const consumer = kafka.consumer({ groupId });
  const messages = [];
  const remaining = new Map(offsets.map((o) => [o.partition, Number(o.high) - Number(o.low)]));
  await consumer.connect();
  try {
    await consumer.subscribe({ topic, fromBeginning: true });
    await new Promise((resolve, reject) => {
      const timer = setTimeout(resolve, 5000);
      consumer.run({
        eachMessage: async ({ partition, message }) => {
          const h = Object.fromEntries(Object.entries(message.headers ?? {}).map(([k, v]) => [k, decodeHeader(v)]));
          messages.push({
            partition,
            offset: message.offset,
            timestamp: new Date(Number(message.timestamp)).toISOString(),
            key: message.key?.toString() ?? null,
            value: message.value?.toString() ?? null,
            originalTopic: h['kafka_dlt-original-topic'] ?? null,
            originalOffset: h['kafka_dlt-original-offset'] ?? null,
            exceptionClass: h['kafka_dlt-exception-fqcn'] ?? null,
            exceptionMessage: h['kafka_dlt-exception-message'] ?? null,
            causeClass: h['kafka_dlt-exception-cause-fqcn'] ?? null,
          });
          remaining.set(partition, (remaining.get(partition) ?? 1) - 1);
          if ([...remaining.values()].every((n) => n <= 0)) {
            clearTimeout(timer);
            resolve();
          }
        },
      }).catch((e) => {
        // 구독 시작이 실패하면 5초 뒤 빈 목록이 아니라 오류로 돌려준다
        clearTimeout(timer);
        reject(e);
      });
    });
  } finally {
    await consumer.disconnect().catch(() => {});
    const admin2 = kafka.admin();
    await admin2.connect().then(() => admin2.deleteGroups([groupId])).catch(() => {}).finally(() => admin2.disconnect().catch(() => {}));
  }
  messages.sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  return [200, { topic, total, messages: messages.slice(0, limit) }];
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(data);
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method !== 'GET') return send(res, 405, { message: '읽기 전용 서버예요' });
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/ops/health') return send(res, 200, { ok: true });
    const claims = verifyJwt(req.headers.authorization);
    if (!claims) return send(res, 401, { message: '로그인이 필요해요' });

    let m;
    if ((m = url.pathname.match(/^\/ops\/accounts\/([^/]+)\/ledger$/)) && UUID.test(m[1])) {
      const [s, b] = await ledger(m[1], claims.sub);
      return send(res, s, b);
    }
    if ((m = url.pathname.match(/^\/ops\/subscriptions\/([^/]+)\/trace$/)) && UUID.test(m[1])) {
      const [s, b] = await trace(m[1], claims.sub);
      return send(res, s, b);
    }
    // DLT 메시지 내용. 다른 사용자의 ID가 들어 있을 수 있어 운영에서는 ADMIN만 보도록 바꿔야 한다
    if (url.pathname === '/ops/dlt') {
      const [s, b] = await dltMessages(url.searchParams.get('topic') ?? '', 20);
      return send(res, s, b);
    }
    // 시스템 전체 집계 (개인 정보 없음). 운영에서는 ADMIN만 보도록 바꿔야 한다
    if (url.pathname === '/ops/summary') {
      const [s, b] = await summary();
      return send(res, s, b);
    }
    return send(res, 404, { message: '없는 경로예요' });
  } catch (e) {
    console.error(`[ops] ${req.method} ${req.url} 실패: ${e.message}`);
    return send(res, 500, { message: '운영 조회 실패', error: e.message });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`시연용 운영 조회 서버 http://127.0.0.1:${PORT} (읽기 전용)`);
});
