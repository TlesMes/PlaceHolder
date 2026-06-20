export function formatDateTime(value) {
  if (!value) return '';
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatPoint(value) {
  return `${Number(value).toLocaleString('ko-KR')}P`;
}

export function formatPrice(value) {
  return `${Number(value).toLocaleString('ko-KR')}원`;
}

// 백엔드 LocalDateTime(@DateTimeFormat ISO.DATE_TIME, 타임존 없음)용 문자열.
// Date.toISOString()은 UTC 'Z'라 어긋나므로 로컬 시각을 'yyyy-MM-ddTHH:mm:ss'로 직접 만든다.
export function toLocalDateTimeString(date) {
  const p = (n) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}` +
    `T${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`
  );
}

// N개월 전 시각의 LocalDateTime 문자열 (포인트 이력 기간 필터 from).
export function monthsAgoLocalDateTime(months) {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  return toLocalDateTimeString(d);
}
