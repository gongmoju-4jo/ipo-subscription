import { Link } from 'react-router-dom';
import { useOpsSummary } from '../api/queries';
import { Badge, Card } from '../components/ui';

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
    measured: '목표 피크 433건/초에서 p95 1.44초, 소진 0.74초로 기준 이내. 대량 환불은 미측정. 600건/초는 목표보다 높은 부하에서 확인한 한계(p95 23.5초)로, 목표 실패와 구분',
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

const IMPROVE = [
  { run: '측정-6 (기준)', change: '-', p95: '54.47초', rate: '약 285건/초' },
  { run: '측정-8', change: 'PostgreSQL CPU 1 → 2', p95: '36.09초', rate: '약 345건/초' },
  { run: '측정-9', change: 'Kafka 파티션, Consumer 3 → 6', p95: '1.44초', rate: '약 510건/초' },
];

const NEXT: { tag: string; what: string; why: string; source: string }[] = [
  { tag: '성능', what: '대량 환불 처리 시간 측정', why: 'G2의 남은 지표. 환불 건수는 LG에너지솔루션 KB증권 청약 건수(DART)를 근거로 정하고, 목표 시간은 정하는 중', source: 'SA1 G2' },
  { tag: '성능', what: '600건/초에서 확정 지연 개선', why: '목표보다 높은 600건/초에서 한계 확인: 확정 p95 23.5초로 기준(15초) 초과. 증거금 인출 Consumer 병목부터 개선 전후 같은 조건으로 측정', source: '정식측정-9, #92' },
  { tag: '정합성', what: '원장 대사 배치', why: '잔고와 원장, 서비스 간 증거금과 배정 금액을 정기적으로 맞춰 보고 어긋나면 알림. 지금은 검사 SQL을 손으로 실행', source: 'SA1 도전 과제' },
  { tag: '정합성', what: '접수도 Outbox로', why: '마감 직전 접수가 늦게 발행되면 마감 뒤에 들어올 수 있어요. 청약 행과 다음 이벤트를 한 트랜잭션에 저장하고, 마감은 DB만 보고 판단', source: '이벤트 명세 위험 4' },
  { tag: '도메인', what: '증거금률 50%', why: '실제 공모주는 보통 50%. 배정 대금이 증거금보다 커질 수 있어 추가 납입 흐름이 생기고, 정산과 정합성 검사식도 같이 바뀜', source: '유비쿼터스 언어, SA1 F-10' },
  { tag: '비교', what: 'Redis와 DB 멱등 처리 비교', why: '지금은 DB의 이벤트 처리 기록으로 중복을 막아요. Redis로 바꿨을 때 처리량과 장애 시 안전성을 같은 조건으로 비교', source: 'SA1 도전 과제' },
  { tag: '운영', what: 'DLT 알림과 재처리', why: 'MVP는 실패 메시지를 잃지 않게 DLT에 보관하는 데까지(Kafka UI로 확인). 원인을 고치기 전에 자동으로 다시 보내면 같은 실패가 반복돼서, DLT가 쌓이면 알리고 원인 수정 뒤 원래 토픽으로 재처리. 이벤트 처리 기록 덕분에 다시 보내도 한 번만 반영', source: '통합 테스트 ACC-12' },
  { tag: '운영', what: 'Slack 알림 → 관리자 API로 수동 처리', why: 'DLT나 정산 불일치가 생기면 Slack으로 알리고, 관리자(ADMIN)가 관리자 API로 DLT 메시지를 다시 처리하거나 멈춘 증거금을 반환하는 등 직접 복구', source: '통합 테스트 ACC-12, PR #75' },
  { tag: '기능', what: '청약 취소', why: '취소 API가 없어 통합 테스트 1건(ACC-6)을 실행하지 못함', source: '11/5 통합 테스트' },
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
              {GOALS.map((g, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{g.id} {g.name}</td>
                  <td className="sub">{g.target}</td>
                  <td style={{ fontWeight: 700 }}>{g.measured}</td>
                  <td>
                    {g.status === 'achieved' ? <Badge tone="success">달성</Badge> : g.status === 'partial' ? <Badge tone="progress">부분 달성</Badge> : <Badge tone="muted">미측정</Badge>}
                  </td>
                  <td className="hint">{g.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div className="grid grid-2">
        <Card title="병목을 찾아 개선한 결과" extra={<span className="hint">같은 조건 재측정</span>}>
          <table className="table">
            <thead><tr><th>측정</th><th>바꾼 것</th><th className="r">433건/초 확정 p95</th><th className="r">확정 처리 속도</th></tr></thead>
            <tbody>
              {IMPROVE.map((r) => (
                <tr key={r.run}><td>{r.run}</td><td>{r.change}</td><td className="r num" style={{ fontWeight: 700 }}>{r.p95}</td><td className="r num">{r.rate}</td></tr>
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
              <div className="hint">검사 {ops.data.checks.length}개를 지금 DB에 실행한 결과예요. 자세한 내용은 시스템 현황에서 볼 수 있어요</div>
              <Link to="/system" className="btn btn-ghost btn-sm" style={{ width: 'fit-content' }}>시스템 현황 보기</Link>
            </div>
          )}
        </Card>
      </div>
      <Card title={<>로드맵 <small>트러블슈팅, 고도화 기간</small></>} extra={<Badge tone="muted">계획</Badge>}>
        <div className="list">
          {NEXT.map((n) => (
            <div key={n.what} className="list-item">
              <div>
                <div className="item-title" style={{ fontSize: 15 }}><span className="tag">{n.tag}</span>{n.what}</div>
                <div className="item-meta">{n.why}</div>
              </div>
              <span className="hint" style={{ whiteSpace: 'nowrap' }}>{n.source}</span>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
