import SeatCell from './SeatCell';

export default function SeatGrid({ seats, myHeldSeatId, selectedSeatId, disabled, onSeatClick }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(84px,1fr))] gap-2.5">
      {seats.map((seat) => (
        <SeatCell
          key={seat.seatId}
          seat={seat}
          isMine={seat.seatId === myHeldSeatId && seat.status === 'HELD'}
          isSelected={seat.seatId === selectedSeatId}
          disabled={disabled}
          onClick={onSeatClick}
        />
      ))}
    </div>
  );
}
