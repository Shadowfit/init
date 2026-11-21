/**
 * 측정된 반복이 없는 세션의 안내 — 서버가 알려 준 «이유»(measurementStatus, V37)로 무엇을 고쳐 다시 찍을지 가른다.
 * 이유를 모르면(구버전 AI·옛 세션·모르는 새 값) 예전과 같은 일반 안내다.
 *
 * 순수 함수라 node 로 시험된다: `node utils/unmeasuredMessage.test.cjs`
 */
export function unmeasuredMessage(status: string | null | undefined): string {
  switch (status) {
    case 'NO_POSE':
      return '화면에서 사람을 찾지 못했어요. 카메라가 나를 향해 있는지, 밝은 곳인지 확인하고 다시 시도해보세요.';
    case 'NOT_FULLY_VISIBLE':
      return '몸 일부가 화면 밖에 있었어요. 머리부터 발목까지 전신이 화면에 들어오게 서서 다시 시도해보세요.';
    case 'NO_REP':
      return '사람은 보였지만 인정된 반복이 없었어요. 충분히 내려갔다 올라오는 동작을 끝까지 해보세요.';
    default:
      return '이번 운동에서는 측정된 반복이 없어요. 전신이 화면에 들어오게 서서 다시 시도해보세요.';
  }
}
