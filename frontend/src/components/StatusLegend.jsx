import { LEGEND } from '../lib/seatStyle';

export default function StatusLegend() {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
      {LEGEND.map((item) => (
        <span key={item.key} className="flex items-center gap-1.5 text-xs text-fg-muted">
          <span className={`h-2.5 w-2.5 rounded-full ${item.dot}`} />
          {item.label}
        </span>
      ))}
    </div>
  );
}
