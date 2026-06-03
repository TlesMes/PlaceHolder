import { useEffect, useState } from 'react';

// heldUntil(ISO)까지 남은 시간을 mm:ss로 1초마다 갱신해 보여준다.
export default function Countdown({ until, className = '' }) {
  const [remaining, setRemaining] = useState(() => diff(until));

  useEffect(() => {
    // 외부 타이머(1초 틱)와 남은 시간을 동기화한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRemaining(diff(until));
    const id = setInterval(() => setRemaining(diff(until)), 1000);
    return () => clearInterval(id);
  }, [until]);

  if (remaining <= 0) return <span className={className}>만료됨</span>;

  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  return (
    <span className={className}>
      {m}:{String(s).padStart(2, '0')}
    </span>
  );
}

function diff(until) {
  if (!until) return 0;
  return Math.max(0, Math.floor((new Date(until).getTime() - Date.now()) / 1000));
}
