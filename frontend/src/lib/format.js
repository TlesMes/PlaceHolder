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
