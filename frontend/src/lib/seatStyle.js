// 좌석 상태별 시각 스타일을 한 곳에 정의한다 (SeatCell / StatusLegend 공유).
// SELECTED, MINE는 status가 아닌 파생 상태이며, SeatCell에서 별도 적용한다.
export const SEAT_STYLE = {
  AVAILABLE: {
    label: '예약 가능',
    cell: 'bg-success-soft text-success-soft-fg ring-1 ring-inset ring-success/40 hover:ring-success hover:shadow-sm cursor-pointer',
    dot: 'bg-success',
  },
  HELD: {
    label: '홀드 중',
    cell: 'bg-warning-soft text-warning-soft-fg ring-1 ring-inset ring-warning/40 cursor-not-allowed animate-pulse',
    dot: 'bg-warning',
  },
  CONFIRMED: {
    label: '확정됨',
    cell: 'bg-danger-soft text-danger-soft-fg ring-1 ring-inset ring-danger/40 opacity-70 cursor-not-allowed',
    dot: 'bg-danger',
  },
};

// 파생 상태 스타일.
export const SELECTED_CELL =
  'bg-success-soft text-success-soft-fg ring-2 ring-inset ring-primary shadow-sm cursor-pointer';
export const MINE_HELD_CELL =
  'bg-warning-soft text-warning-soft-fg ring-2 ring-inset ring-warning shadow-sm cursor-pointer';

// 좌석의 "유효 상태"를 계산한다.
// 서버는 lazy 만료 정책(ADR-008)이라 status=HELD여도 heldUntil이 지났으면 재점유 가능하다.
// 프론트도 이 규칙을 반영해, 만료된 HELD는 AVAILABLE로 취급한다.
export function effectiveStatus(seat) {
  if (seat.status === 'HELD' && seat.heldUntil) {
    if (new Date(seat.heldUntil).getTime() <= Date.now()) return 'AVAILABLE';
  }
  return seat.status;
}

export const LEGEND = [
  { key: 'AVAILABLE', ...SEAT_STYLE.AVAILABLE },
  { key: 'HELD', ...SEAT_STYLE.HELD },
  { key: 'CONFIRMED', ...SEAT_STYLE.CONFIRMED },
];
