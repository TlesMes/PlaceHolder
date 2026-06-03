import { SEAT_STYLE, SELECTED_CELL, MINE_HELD_CELL, effectiveStatus } from '../lib/seatStyle';
import { formatPrice } from '../lib/format';
import Countdown from './Countdown';

export default function SeatCell({ seat, isMine, isSelected, disabled, onClick }) {
  // 만료된 HELD는 AVAILABLE로 취급 (서버 lazy 만료와 일치).
  const status = effectiveStatus(seat);
  const base = SEAT_STYLE[status] ?? SEAT_STYLE.AVAILABLE;

  // 파생 상태 우선순위: 내 홀드 > 선택 > 기본 상태.
  let cellClass = base.cell;
  if (isMine) cellClass = MINE_HELD_CELL;
  else if (isSelected) cellClass = SELECTED_CELL;

  // 클릭 가능: 내 홀드(확정 대상)이거나 (유효상태가) AVAILABLE. disabled면 모두 잠금.
  const clickable = !disabled && (isMine || status === 'AVAILABLE');

  return (
    <button
      type="button"
      disabled={!clickable}
      onClick={() => clickable && onClick(seat)}
      title={`${seat.label} · ${formatPrice(seat.price)}`}
      className={`relative flex flex-col items-center justify-center gap-0.5 rounded-lg px-2 py-3 text-center transition disabled:cursor-not-allowed ${cellClass}`}
    >
      {isMine && (
        <span className="absolute -top-2 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full bg-amber-500 px-1.5 py-0.5 text-[10px] font-semibold text-white shadow">
          내 홀드
        </span>
      )}
      <span className="text-sm font-semibold">{seat.label}</span>
      <span className="text-xs opacity-80">{formatPrice(seat.price)}</span>
      {isMine && seat.heldUntil && (
        <Countdown until={seat.heldUntil} className="mt-0.5 text-[11px] font-medium tabular-nums" />
      )}
    </button>
  );
}
