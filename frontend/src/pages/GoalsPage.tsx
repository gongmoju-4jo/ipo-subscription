import { Fragment } from 'react';
import { Link } from 'react-router-dom';
import { useOpsSummary } from '../api/queries';
import { Badge, Card } from '../components/ui';
import { DesignNote } from '../demo/DesignNote';

// 성과와 로드맵 화면. 목표값(SA 문서)과 측정값(기록된 측정 결과)을 구분하고 출처를 같이 적는다
// 측정값은 측정 때 남긴 결과 파일의 값이다. 화면이 계산하거나 지어내지 않는다

interface GoalRow {
  id: string;
  name: string;
  target: string;
  measured: string;
  status: 'achieved' | 'partial' | 'unmeasured';
  source: string;
}

const GOALS: GoalRow[] = [
  {
    id: 'G1',
    name: '접수 성능',
    target: '400건/초 이상, 평균 250ms 이하, p95 500ms 이하, 오류 0%',
    measured: '433건/초, 평균 8.0ms, p95 41.4ms, 오류 0',
    status: 'achieved',
    source: '정식측정-9 (official-1104-e, 11/4, 단계마다 3회 중앙값, compose.perf.yml 자원 상한)',
  },
  {
    id: 'G2',
    name: '확정 속도',
    target: '접수→확정 p95 15초 이하, 소진 15초 이하',
    measured: '목표 피크 433건/초에서 p95 1.44초, 소진 0.74초로 기준 이내.\n대량 환불은 미측정.\n600건/초는 목표보다 높은 부하에서 확인한 한계(p95 23.5초)로, 목표 실패와 구분',
    status: 'partial',
    source: '정식측정-9 (official-1104-e)',
  },
  {
    id: 'G3',
    name: '장애 대응',
    target: '재현한 실패에서 상태 불일치 0',
    measured: '실패 10가지 재현, 불일치 0 (확정 실패 → 11.0초 만에 전액 반환)',
    status: 'achieved',
    source: '11/5 통합 테스트 (develop 5e1ed23)',
  },
  {
    id: 'G4',
    name: '금액 정합성',
    target: '검증식 위반 0',
    measured: '청약 1,000건 처리 후 검사 1번부터 10번 위반 0',
    status: 'achieved',
    source: '11/5 통합 테스트 E2E-3 (verify.sql)',
  },
  {
    id: 'G2',
    name: '대량 환불 시간',
    target: '환불 N건 처리 시간 (목표값 정하는 중)',
    measured: '측정 전 (측정 후 수정 예정)',
    status: 'unmeasured',
    source: 'SA1 G2 지표, 아래 로드맵 참고',
  },
];

// 정식 측정 official-1105-s34 (PR, Closes #102) 본문 표의 3회 중앙값
const SCENARIOS = [
  { id: 'S0 일반', what: '없음. 회원 1,000명이 1건씩', tps: '941.9', avg: '4.1ms', p95: '1.39초', check: '-' },
  { id: 'S3 중복', what: '같은 요청 재전송 80, 다른 계좌로 같은 종목 60', tps: '948.1', avg: '2.0ms', p95: '1.19초', check: '청약, 돈 1번만' },
  { id: 'S4 잔액 부족', what: '1,000명 중 97명 예수금 부족', tps: '945.2', avg: '3.1ms', p95: '1.01초', check: '거절 97명 돈 이동 0' },
];

// 목표 수치의 근거 (SA1 "G1 목표 수치의 근거", "G1 성공 기준" 표). [가정]은 가정이라고 그대로 적는다
const WHY = [
  { num: '433건/초', basis: 'LG에너지솔루션 KB증권 청약 2,122,367건(DART, 2022-01-21) 중 1일차 1,299,764건.\n오픈 직후 10분에 20%가 몰린다고 보고 1,299,764 x 20% ÷ 600초.\n20%는 가정 (시간대별 자료 없음.\n10%면 217건/초)' },
  { num: '목표 400건/초', basis: '위 피크 433건/초를 받아내기 위한 목표' },
  { num: '합격 200건/초, 평균 500ms', basis: '발제 권장 기준선.\n동시 100명이 200건/초를 내려면 한 건이 평균 0.5초 안에 끝나야 함 (100 ÷ 0.5초)' },
  { num: '100명 x 10회', basis: '발제 부하 조건 (1초에 100명 시작, 각 10회)' },
  { num: '600건/초', basis: '목표보다 높은 부하에서 어디서 한계가 오는지 확인하려고 추가한 단계' },
  { num: '환불 212만 건', basis: '같은 KB증권 청약 건수.\n경쟁률 약 69:1이라 거의 모든 청약에 환불이 생김' },
];

// 부하 조건의 근거 (docs/LOADTEST.md, 시연 시나리오 측정 조건 표, 정식측정-9). 근거가 기록되지 않은 값은 넣지 않는다
const WHY_LOAD = [
  { num: '1,000건 (100명 x 10회)', basis: '발제가 정한 동시성 확인 조건이라 그대로 씀.\n우리 서비스 기준 부하는 이게 아니라 실제 청약 건수(DART)로 계산한 433건/초(S1).\n한 사람은 공모주마다 1건만 청약할 수 있어서 회원도 1,000명을 만듦' },
  { num: '워밍업 (최소 3분, 최대 15분)', basis: '기동 직후에는 JVM 컴파일, 커넥션 풀, DB 캐시가 덜 데워져 느림.\n응답 시간 추세가 평평해질 때까지 돌리고 그 구간은 판정에서 뺌' },
  { num: '단계 100, 200, 300, 433, 600건/초', basis: '433은 목표 피크, 600은 목표를 넘겨 한계가 어디서 오는지 보려고.\n속도를 고정(도착률 고정)해서 느려져도 요청 수가 줄지 않게 함' },
  { num: '같은 조건 3회, 중앙값', basis: '1초 남짓에 끝나는 시험은 회차마다 흔들림이 커서 한 번 값은 우연일 수 있음' },
  { num: '회원 1,000명, 요청마다 새 requestId', basis: '같은 회원, 같은 요청만 보내면 캐시와 중복 처리로 실제보다 빠르게 나옴' },
  { num: '측정마다 같은 시작 DB', basis: '회원 1,000명, 청약 0건 스냅샷으로 되돌림.\n데이터가 쌓여 DB 크기가 달라지는 것을 막음' },
  { num: '실행 사이 30초 쉬기', basis: '앞 실행의 서버 지표(5초 수집)가 다음 실행 구간에 섞이지 않게' },
  { num: '컨테이너마다 CPU, 메모리 상한', basis: '측정마다 같은 자원을 쓰게 고정.\n배포 서버도 같은 값' },
  { num: '파티션, Consumer 6', basis: '3일 때 확정 속도가 약 345건/초에서 막혀 6으로 늘림 → 약 510건/초 (측정-8 → 9)' },
  { num: 'S3, S4 비율', basis: 'LLM이 시나리오를 가정해 정한 값 (실제 통계 아님)' },
];

// S1 단계별 결과 (정식측정-9 official-1104-e, 단계마다 3회 중앙값)
const S1_STAGES = [
  { rate: '100', lat: '4.9 / 23.2ms', confirm: '0.99 / 0.55초', ok: true },
  { rate: '200', lat: '5.5 / 33.2ms', confirm: '1.00 / 0.80초', ok: true },
  { rate: '300', lat: '6.4 / 38.3ms', confirm: '1.09 / 1.16초', ok: true },
  { rate: '433 (목표 피크)', lat: '8.0 / 41.4ms', confirm: '1.44 / 0.74초', ok: true },
  { rate: '600 (목표 초과)', lat: '10.2 / 43.4ms', confirm: '23.50 / 32.24초', ok: false },
];

const IMPROVE = [
  { run: '측정-6 (기준)', change: '처음 설정 (서비스, DB CPU 1, 파티션 3)', p95: '54.47초', rate: '약 285건/초' },
  { run: '측정-7', change: '청약 서비스 CPU 1 → 2 (가설: 청약 서비스 CPU 부족).\n결과 변화 없음 → 가설 기각', p95: '54.63초', rate: '측정-6과 같음' },
  { run: '측정-8', change: 'PostgreSQL CPU 1 → 2', p95: '36.09초', rate: '약 345건/초' },
  { run: '측정-9', change: 'Kafka 파티션, Consumer 3 → 6', p95: '1.44초', rate: '약 510건/초' },
];

// 지금의 한계와 이어지는 로드맵 항목
const LIMITS = [
  { what: 'DLT 메시지는 Kafka UI로 사람이 확인하고 재처리도 수동', next: 'DLT 알림과 재처리, 관리자 API' },
  { what: '목표를 넘는 600건/초에서는 확정이 15초를 넘음', next: '600건/초 확정 지연 개선' },
  { what: '대량 환불 처리 시간은 아직 측정하지 않음', next: '대량 환불 측정' },
  { what: '측정은 PC 1대에서 부하 도구와 서비스가 CPU를 나눠 씀', next: '-' },
];

const NEXT: { tag: string; what: string; why: string; source: string }[] = [
  { tag: '성능', what: '대량 환불 처리 시간 측정', why: 'G2의 남은 지표.\n환불 건수는 LG에너지솔루션 KB증권 청약 건수(DART)를 근거로 정하고, 목표 시간은 정하는 중', source: 'SA1 G2' },
  { tag: '성능', what: '600건/초에서 확정 지연 개선', why: '목표보다 높은 600건/초에서 한계 확인: 확정 p95 23.5초로 기준(15초) 초과.\n증거금 인출 Consumer 병목부터 개선 전후 같은 조건으로 측정', source: '정식측정-9, #92' },
  { tag: '정합성', what: '접수도 Outbox로', why: '마감 직전 접수가 늦게 발행되면 마감 뒤에 들어올 수 있어요.\n청약 행과 다음 이벤트를 한 트랜잭션에 저장하고, 마감은 DB만 보고 판단', source: '이벤트 명세 위험 4' },
  { tag: '정합성', what: '원장 대사 배치', why: '잔고와 원장, 서비스 간 증거금과 배정 금액을 정기적으로 맞춰 보고 어긋나면 알림.\n지금은 검사 SQL을 손으로 실행', source: 'SA1 도전 과제' },
  { tag: '운영', what: 'DLT 알림과 재처리', why: 'MVP는 실패 메시지를 잃지 않게 DLT에 보관하는 데까지(Kafka UI로 확인).\n원인을 고치기 전에 자동으로 다시 보내면 같은 실패가 반복돼서, DLT가 쌓이면 알리고 원인 수정 뒤 원래 토픽으로 재처리.\n이벤트 처리 기록 덕분에 다시 보내도 한 번만 반영', source: '통합 테스트 ACC-12' },
  { tag: '운영', what: 'Slack 알림 → 관리자 API로 수동 처리', why: 'DLT나 정산 불일치가 생기면 Slack으로 알리고, 관리자(ADMIN)가 관리자 API로 DLT 메시지를 다시 처리하거나 멈춘 증거금을 반환하는 등 직접 복구', source: '통합 테스트 ACC-12, PR #75' },
  { tag: '비교', what: 'Redis와 DB 멱등 처리 비교', why: '지금은 DB의 이벤트 처리 기록으로 중복을 막아요.\nRedis로 바꿨을 때 처리량과 장애 시 안전성을 같은 조건으로 비교', source: 'SA1 도전 과제' },
  { tag: '기능', what: '청약 취소', why: '취소 API가 없어 통합 테스트 1건(ACC-6)을 실행하지 못함', source: '11/5 통합 테스트' },
  { tag: '아이디어', what: '증거금률 50%', why: '검토 중인 아이디어예요.\n지금은 증거금률 100%로 단순화해서 추가 납입이 없어요. 실제 공모주처럼 50%로 바꾸면 추가 납입 흐름과 정산, 정합성 검사식이 같이 바뀌어요', source: '유비쿼터스 언어' },
];

export function GoalsPage() {
  const ops = useOpsSummary();
  const liveViolations = ops.data ? ops.data.checks.reduce((a, c) => a + (c.violations ?? 0), 0) : null;
  const liveUnknown = ops.data ? ops.data.checks.some((c) => c.violations === null) : true;

  return (
    <div className="stack">
      <div>
        <div className="page-title">성과와 로드맵</div>
        <p className="page-sub">목표값은 기획 문서(SA), 측정값은 측정 때 남긴 결과 파일 기준이에요. 출처를 같이 적었고, 아직 못 한 것은 로드맵에 따로 적었어요</p>
      </div>

      <section className="card hero">
        <div className="label">MVP 목표</div>
        <div className="big" style={{ marginTop: 4 }}>G1, G3, G4 달성. G2 부분 달성</div>
        <div style={{ marginTop: 8, opacity: 0.85 }}>G2: 목표 피크(433건/초)에서 확정 시간은 기준 이내, 대량 환불 시간은 미측정</div>
        <div className="muted" style={{ marginTop: 8 }}>청약 1건이 접수부터 환불까지 서비스 3개를 거쳐 완료되고, 실패가 발생해도 증거금이 묶이거나 금액 불일치가 발생하지 않는다</div>
      </section>

      <Card title="목표 대비 측정값">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>목표</th><th>목표값</th><th>측정값</th><th>판정</th><th>출처</th></tr></thead>
            <tbody>
              {GOALS.map((g) => (
                <tr key={`${g.id}-${g.name}`}>
                  <td style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{g.id} {g.name}</td>
                  <td className="sub">{g.target}</td>
                  <td style={{ fontWeight: 700 }}><span className="lines">{g.measured}</span></td>
                  <td>
                    {g.status === 'achieved' ? <Badge tone="success">달성</Badge> : g.status === 'partial' ? <Badge tone="progress">부분 달성</Badge> : <Badge tone="muted">미측정</Badge>}
                  </td>
                  <td className="hint">{g.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="stack" style={{ marginTop: 12, gap: 8 }}>
          <DesignNote title="왜 이 수치로 잡았나">
            <dl className="kv">
              {WHY.map((w) => (
                <Fragment key={w.num}><dt style={{ whiteSpace: 'nowrap' }}>{w.num}</dt><dd className="lines" style={{ textAlign: 'left' }}>{w.basis}</dd></Fragment>
              ))}
            </dl>
          </DesignNote>
        </div>
      </Card>

      <Card title={<>S1 속도를 올려가며 <small>초당 요청을 단계마다 90초씩 고정해 어디서 한계가 오는지</small></>} extra={<span className="hint">단계마다 3회 가운데 값</span>}>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>초당 요청</th><th className="r">응답 평균 / p95</th><th className="r">접수→확정 p95 / 소진</th><th>판정</th></tr></thead>
            <tbody>
              {S1_STAGES.map((r) => (
                <tr key={r.rate}>
                  <td style={{ fontWeight: 700 }}>{r.rate}</td>
                  <td className="r num">{r.lat}</td>
                  <td className="r num" style={{ fontWeight: 700 }}>{r.confirm}</td>
                  <td>{r.ok ? <Badge tone="success">기준 이내</Badge> : <Badge tone="warn">확정 15초 초과</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="hint" style={{ marginTop: 10 }}>출처: 정식측정-9 (official-1104-e). 600건/초는 목표보다 높은 부하에서 확인한 한계</div>
      </Card>

      <Card title={<>실패가 섞여도 괜찮은지 <small>1,000건 중 일부를 재전송, 이중청약, 잔액 부족으로 (LLM이 만든 가상 사용자 행동)</small></>} extra={<span className="hint">3회 가운데 값</span>}>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>시나리오</th><th>섞은 것</th><th className="r">처리량</th><th className="r">응답 평균</th><th className="r">확정 p95</th><th>정합성</th></tr></thead>
            <tbody>
              {SCENARIOS.map((r) => (
                <tr key={r.id}>
                  <td style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{r.id}</td>
                  <td>{r.what}</td>
                  <td className="r num" style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{r.tps}건/초</td>
                  <td className="r num" style={{ whiteSpace: 'nowrap' }}>{r.avg}</td>
                  <td className="r num" style={{ whiteSpace: 'nowrap' }}>{r.p95}</td>
                  <td>{r.check === '-' ? <span className="hint">-</span> : <Badge tone="success">{r.check}</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="hint" style={{ marginTop: 10 }}>거절, 재전송이 10-14% 섞여도 응답과 확정 시간이 나빠지지 않음. 출처: 정식 측정 official-1105-s34 (#102)</div>
        <div style={{ marginTop: 12 }}>
          <DesignNote title="부하 조건은 왜 이렇게 잡았나">
            <dl className="kv">
              {WHY_LOAD.map((w) => (
                <Fragment key={w.num}><dt style={{ whiteSpace: 'nowrap' }}>{w.num}</dt><dd className="lines" style={{ textAlign: 'left' }}>{w.basis}</dd></Fragment>
              ))}
            </dl>
          </DesignNote>
        </div>
      </Card>

      <div className="grid grid-wide-left">
        <Card title="병목을 찾아 개선한 결과" extra={<span className="hint">같은 조건 재측정</span>}>
          <table className="table">
            <thead><tr><th>측정</th><th>바꾼 것</th><th className="r">433건/초 확정 p95</th><th className="r">확정 처리 속도</th></tr></thead>
            <tbody>
              {IMPROVE.map((r) => (
                <tr key={r.run}><td>{r.run}</td><td><span className="lines">{r.change}</span></td><td className="r num" style={{ fontWeight: 700 }}>{r.p95}</td><td className="r num">{r.rate}</td></tr>
              ))}
            </tbody>
          </table>
          <div className="hint" style={{ marginTop: 10 }}>출처: 정식측정-6부터 9 개선 전후 비교 (11/4)</div>
        </Card>

        <Card title={<>지금 이 DB의 정합성 <span className="pill-ops">실시간</span></>}>
          {ops.error ? (
            <div className="notice gray">운영 조회 서버가 실행 중이 아니라 지금 값은 확인 불가예요. 기록된 결과는 왼쪽 표를 보세요</div>
          ) : !ops.data ? (
            <div className="hint">불러오는 중…</div>
          ) : (
            <div className="stack">
              <div className="big">{liveUnknown ? '일부 확인 불가' : liveViolations === 0 ? '위반 0' : `위반 ${liveViolations}`}</div>
              <div className="hint">검사 {ops.data.checks.length}개를 지금 DB에 실행한 결과예요.<br />자세한 내용은 시스템 현황에서 볼 수 있어요</div>
              <Link to="/system" className="btn btn-ghost btn-sm" style={{ width: 'fit-content' }}>시스템 현황 보기</Link>
            </div>
          )}
        </Card>
      </div>
      <Card title={<>지금의 한계 <small>로드맵으로 개선</small></>}>
        <div className="list">
          {LIMITS.map((l) => (
            <div key={l.what} className="list-item" style={{ padding: '10px 0' }}>
              <div style={{ fontSize: 14 }}>{l.what}</div>
              <span className="hint" style={{ whiteSpace: 'nowrap' }}>{l.next === '-' ? '' : `→ ${l.next}`}</span>
            </div>
          ))}
        </div>
      </Card>

      <Card title={<>로드맵 <small>트러블슈팅, 고도화 기간</small></>} extra={<Badge tone="muted">계획</Badge>}>
        <div className="list">
          {NEXT.map((n) => (
            <div key={n.what} className="list-item">
              <div>
                <div className="item-title" style={{ fontSize: 15 }}><span className="tag">{n.tag}</span>{n.what}</div>
                <div className="item-meta lines">{n.why}</div>
              </div>
              <span className="hint" style={{ whiteSpace: 'nowrap' }}>{n.source}</span>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
