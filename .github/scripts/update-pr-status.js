// PR 본문의 CI 결과 구간(<!-- ci-status:start --> ~ <!-- ci-status:end -->)만 갱신한다.
// 본문 나머지는 건드리지 않는다. 구간이 없으면 본문 끝에 추가한다.
// 환경변수 CI_STATE: pending | success | failure | cancelled | skipped

const START = '<!-- ci-status:start -->';
const END = '<!-- ci-status:end -->';

const VIEW = {
  pending: { build: '⏳ 진행 중', merge: '⏳ CI 결과 대기' },
  success: { build: '⭕ 통과', merge: '✅ 가능 (CI 기준)' },
  failure: { build: '❌ 실패', merge: '⛔ 불가 (CI 실패)' },
  cancelled: { build: '⚠️ 취소됨', merge: '⏳ 새 실행 대기' },
  skipped: { build: '⚠️ 실행되지 않음', merge: '⏳ CI 결과 없음' },
};

module.exports = async ({ github, context }) => {
  const state = process.env.CI_STATE;
  const view = VIEW[state] ?? VIEW.skipped;
  const pr = context.payload.pull_request;
  const sha = pr.head.sha.slice(0, 7);
  const runUrl = `${context.serverUrl}/${context.repo.owner}/${context.repo.repo}/actions/runs/${context.runId}`;

  const block = [
    START,
    '## CI 결과',
    '| 항목 | 결과 |',
    '|---|---|',
    `| 빌드, 테스트 | ${view.build} |`,
    `| 머지 | ${view.merge} |`,
    '',
    `- 커밋 \`${sha}\` · [실행 로그](${runUrl})`,
    END,
  ].join('\n');

  // 갱신 시점의 최신 본문을 다시 읽는다 (작성자가 그 사이에 고쳤을 수 있으므로)
  const { data } = await github.rest.pulls.get({
    ...context.repo,
    pull_number: pr.number,
  });
  const body = data.body ?? '';

  const pattern = new RegExp(`${START}[\\s\\S]*?${END}`);
  const next = pattern.test(body)
    ? body.replace(pattern, () => block)
    : `${body}${body ? '\n\n' : ''}${block}`;

  if (next === body) return;

  await github.rest.pulls.update({
    ...context.repo,
    pull_number: pr.number,
    body: next,
  });
};
