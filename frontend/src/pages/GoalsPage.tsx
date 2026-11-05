import { Link } from 'react-router-dom';
import { useOpsSummary } from '../api/queries';
import { Badge, Card } from '../components/ui';

// 목표 달성 화면. 목표값(SA 문서)과 측정값(기록된 측정 결과)을 구분하고 출처를 같이 적는다
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
    measured: '433건/초에서 p95 1.44초, 소진 0.74초 (600건/초는 p95 23.5초)',
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
    target: '환불 N건 처리 시간',
    measured: '측정 전',
    status: 'unmeasured',
    source: '트러블슈팅 기간 예정',
  },
];

const IMPROVE = [
  { run: '측정-6 (기준)', change: '-', p95: '54.47초', rate: '약 285건/초' },
  { run: '측정-8', change: 'PostgreSQL CPU 1 → 2', p95: '36.09초', rate: '약 345건/초' },
  { run: '측정-9', change: 'Kafka 파티션, Consumer 3 → 6', p95: '1.44초', rate: '약 510건/초' },
];

export function GoalsPage() {
  const ops = useOpsSummary();
  const liveViolations = ops.data ? ops.data.checks.reduce((a, c) => a + (c.violations ?? 0), 0) : null;
  const liveUnknown = ops.data ? ops.data.checks.some((c) => c.violations === null) : true;

  return (
    <div className="stack">
      <div>
        <div className="page-title">목표 달성</div>
        <p className="page-sub">목표값은 기획 문서(SA), 측정값은 측정 때 남긴 결과 파일 기준이에요. 출처를 같이 적었어요</p>
      </div>

      <section className="card hero">
        <div className="label">MVP 목표</div>
        <div className="big" style={{ marginTop: 4 }}>4개 중 3개 달성, 1개는 433건/초까지 달성</div>
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
                    {g.status === 'achieved' ? <Badge tone="success">달성</Badge> : g.status === 'partial' ? <Badge tone="progress">433건/초까지</Badge> : <Badge tone="muted">미측정</Badge>}
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
    </div>
  );
}
