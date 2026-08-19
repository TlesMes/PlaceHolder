/**
 * 포인트 소모 계층 배분 — 표시용 추정 (ADR-020).
 *
 * ⚠️ 이것은 사용자에게 "어느 재원이 얼마나 쓰이는지" 미리 보여주기 위한 계산일 뿐이며,
 * 실제 배분은 결제 확정 시점에 서버가 계정 행 락을 쥔 채로 다시 계산한다.
 * 화면을 보는 동안 다른 요청이 잔액을 바꿀 수 있으므로 이 값과 실제 결과가 다를 수 있고,
 * 그래도 금전적 손실은 없다(서버 계산이 유일한 진실이다).
 *
 * 소모 순서는 서버의 PointBucket 선언 순서와 같아야 한다: EVENT → FREE → PAID.
 * 서버 규칙이 바뀌면 이 파일도 함께 바꿔야 하는 중복이 있으며, 이벤트 캐시 만료가
 * 도입될 때 서버 미리보기 엔드포인트로 옮기는 것을 검토한다.
 */

/** 소모 우선순위 — "사라질 것부터, 돌려줄 수 있는 것은 마지막에". */
export const SPEND_ORDER = ['event', 'free', 'paid'];

export const BUCKET_LABELS = {
  event: '이벤트 캐시',
  free: '무료 캐시',
  paid: '유료 캐시',
};

/**
 * @param {{event:number, free:number, paid:number}} balance 계층별 잔액
 * @param {number} amount 결제할 금액
 * @returns {{allocation:{event:number,free:number,paid:number}, total:number, shortfall:number}}
 *          shortfall > 0 이면 잔액이 모자란다(충전 필요).
 */
export function allocateSpend(balance, amount) {
  const allocation = { event: 0, free: 0, paid: 0 };
  let remaining = Math.max(0, Number(amount) || 0);

  for (const bucket of SPEND_ORDER) {
    if (remaining <= 0) break;
    const available = Math.max(0, Number(balance?.[bucket]) || 0);
    const taken = Math.min(remaining, available);
    allocation[bucket] = taken;
    remaining -= taken;
  }

  const total = SPEND_ORDER.reduce((sum, b) => sum + allocation[b], 0);
  return { allocation, total, shortfall: remaining };
}
