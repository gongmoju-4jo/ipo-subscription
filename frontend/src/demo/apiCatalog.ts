// 시연용 API 설명. API 기록 창에서 요청마다 "무엇을 하는 API인지, 어디서 처리하는지, 설계 포인트"를 한글로 보여준다
// 경로는 위에서부터 처음 맞는 것을 쓴다. 맞는 게 없으면 설명 없이 원문만 보여준다

export interface ApiDescription {
  title: string;
  service: string;
  point?: string;
}

const UUID = '[0-9a-f-]{36}';

const CATALOG: { method: string; pattern: RegExp; desc: ApiDescription }[] = [
  {
    method: 'POST', pattern: /^\/api\/v1\/users$/,
    desc: { title: '회원가입', service: 'Gateway → account-service', point: '공개 경로라 토큰 없이 통과해요' },
  },
  {
    method: 'POST', pattern: /^\/api\/v1\/auth\/login$/,
    desc: { title: '로그인 (토큰 발급)', service: 'Gateway → account-service', point: '계좌 서비스가 JWT를 발급하고, 이후 요청은 Gateway가 토큰을 검증한 뒤 X-User-Id를 붙여 서비스로 보내요' },
  },
  {
    method: 'GET', pattern: new RegExp(`^/api/v1/accounts/${UUID}/ledger`),
    desc: { title: '계좌 거래 내역 (원장)', service: 'Gateway → account-service', point: '돈이 움직일 때마다 남긴 기록이에요. 추가만 하고 고치지 않아서, 마지막 기록의 거래 후 잔고가 지금 잔고와 같아야 해요' },
  },
  {
    method: 'POST', pattern: new RegExp(`^/api/v1/accounts/${UUID}/deposits$`),
    desc: { title: '예수금 입금', service: 'Gateway → account-service', point: '계좌를 잠그고 잔고 변경과 원장 기록을 한 트랜잭션으로 처리해요. 같은 requestId로 다시 보내면 한 번만 입금돼요' },
  },
  {
    method: 'GET', pattern: new RegExp(`^/api/v1/accounts/${UUID}$`),
    desc: { title: '계좌 상세 (예수금, 청약증거금)', service: 'Gateway → account-service', point: '본인 계좌만 조회돼요' },
  },
  {
    method: 'POST', pattern: /^\/api\/v1\/accounts$/,
    desc: { title: '계좌 개설', service: 'Gateway → account-service', point: '같은 requestId로 다시 보내면 계좌가 하나만 생겨요' },
  },
  {
    method: 'GET', pattern: /^\/api\/v1\/accounts/,
    desc: { title: '내 계좌 목록', service: 'Gateway → account-service' },
  },
  {
    method: 'POST', pattern: new RegExp(`^/api/v1/queues/${UUID}/entries$`),
    desc: { title: '대기열 진입', service: 'Gateway (Redis, 서비스로 보내지 않음)', point: '공모주별 대기열에 진입해요. 다시 진입해도 처음 순번이 그대로고, 이미 입장 허가를 받았으면 바로 허가 상태를 돌려줘요' },
  },
  {
    method: 'GET', pattern: new RegExp(`^/api/v1/queues/${UUID}/entries/me$`),
    desc: { title: '대기열 순번 조회', service: 'Gateway (Redis)', point: '알려준 간격마다 조회해요. 대기자가 많을수록 간격이 길어져 조회가 몰리지 않고, 조회가 간격의 3배 동안 끊기면 대기열에서 이탈 처리돼요' },
  },
  {
    method: 'DELETE', pattern: new RegExp(`^/api/v1/queues/${UUID}/entries/me$`),
    desc: { title: '대기열 이탈', service: 'Gateway (Redis)', point: '대기열과 입장 허가를 모두 지워요' },
  },
  {
    method: 'GET', pattern: new RegExp(`^/api/v1/ipos/${UUID}$`),
    desc: { title: '공모주 상세', service: 'Gateway → subscription-service' },
  },
  {
    method: 'GET', pattern: /^\/api\/v1\/ipos/,
    desc: { title: '공모주 목록', service: 'Gateway → subscription-service' },
  },
  {
    method: 'POST', pattern: /^\/api\/v1\/subscriptions$/,
    desc: { title: '청약 신청', service: 'Gateway → subscription-service → Kafka', point: '게이트웨이가 먼저 그 공모주의 입장 허가를 확인해요(없으면 403). 요청을 Kafka 토픽에 넣고 바로 202(접수됨)로 응답해요. 실제 처리(증거금 인출, 확정)는 Consumer가 이어서 해요. 같은 requestId로 다시 보내도 청약은 1건이에요' },
  },
  {
    method: 'GET', pattern: new RegExp(`^/api/v1/subscriptions/${UUID}$`),
    desc: { title: '청약 상세 (진행 상태)', service: 'Gateway → subscription-service', point: '접수 직후에는 Consumer가 아직 저장 전이라 404일 수 있어요. 화면은 잠깐 기다렸다 다시 물어봐요' },
  },
  {
    method: 'GET', pattern: /^\/api\/v1\/subscriptions/,
    desc: { title: '내 청약 목록', service: 'Gateway → subscription-service' },
  },
  {
    method: 'GET', pattern: /^\/api\/v1\/allocations\/subscriptions\//,
    desc: { title: '배정 결과', service: 'Gateway → allocation-service', point: '마감 전이면 아직 배정 결과가 없어요' },
  },
  {
    method: 'GET', pattern: /^\/ops\/summary$/,
    desc: { title: '운영 요약 (Outbox, DLT, 정합성 검사)', service: '시연용 운영 조회 서버 (읽기 전용 DB 세션)', point: '정식 API가 아니라 시연 때만 켜는 조회 서버예요' },
  },
  {
    method: 'GET', pattern: /^\/ops\/dlt/,
    desc: { title: 'DLT 메시지 조회', service: '시연용 운영 조회 서버 → Kafka', point: '실패해서 따로 모아 둔 메시지와 실패 원인을 읽어요. 읽기만 하고 지우지 않아요' },
  },
  {
    method: 'GET', pattern: /^\/ops\/subscriptions\/.+\/trace$/,
    desc: { title: '청약 처리 추적 (이벤트, 처리 기록)', service: '시연용 운영 조회 서버 (읽기 전용 DB 세션)', point: '서비스 3개의 Outbox와 이벤트 처리 기록을 모아 한 청약이 어떤 이벤트를 거쳤는지 보여줘요' },
  },
];

export function describeApi(method: string, path: string): ApiDescription | null {
  const clean = path.split('?')[0];
  return CATALOG.find((c) => c.method === method && c.pattern.test(clean))?.desc ?? null;
}
