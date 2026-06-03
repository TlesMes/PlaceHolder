// 좌석 상태별 시각 스타일을 한 곳에 정의한다 (SeatCell / StatusLegend 공유).
// SELECTED, MINE는 status가 아닌 파생 상태이며, SeatCell에서 별도 적용한다.
export const SEAT_STYLE = {
  AVAILABLE: {
    label: '예약 가능',
    cell: 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200 hover:ring-emerald-400 hover:shadow-sm cursor-pointer',
    dot: 'bg-emerald-400',
  },
  HELD: {
    label: '홀드 중',
    cell: 'bg-amber-50 text-amber-700 ring-1 ring-inset ring-amber-200 cursor-not-allowed animate-pulse',
    dot: 'bg-amber-400',
  },
  CONFIRMED: {
    label: '확정됨',
    cell: 'bg-rose-50 text-rose-700 ring-1 ring-inset ring-rose-200 opacity-70 cursor-not-allowed',
    dot: 'bg-rose-400',
  },
};

// 파생 상태 스타일.
export const SELECTED_CELL =
  'bg-emerald-50 text-emerald-800 ring-2 ring-inset ring-indigo-500 shadow-sm cursor-pointer';
export const MINE_HELD_CELL =
  'bg-amber-100 text-amber-800 ring-2 ring-inset ring-amber-400 shadow-sm cursor-pointer';

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
