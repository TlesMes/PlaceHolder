// 백엔드 ErrorResponse(code/message) 또는 HTTP status를 사용자용 메시지로 매핑한다.
const CODE_MESSAGE = {
  SEAT_NOT_AVAILABLE: '이미 선점되었거나 예약할 수 없는 좌석입니다.',
  SEAT_NOT_FOUND: '좌석을 찾을 수 없습니다.',
  SEAT_NOT_HELD_BY_USER: '내가 홀드한 좌석이 아닙니다.',
  INSUFFICIENT_POINT: '포인트가 부족합니다.',
  RESERVATION_NOT_FOUND: '예약 정보를 찾을 수 없습니다.',
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  DUPLICATE_EMAIL: '이미 가입된 이메일입니다.',
};

export function toMessage(err, fallback = '요청을 처리하지 못했습니다.') {
  const data = err?.response?.data;
  const status = err?.response?.status;

  if (data?.code && CODE_MESSAGE[data.code]) return CODE_MESSAGE[data.code];
  if (status === 403) return 'BOOKER 계정으로 로그인해야 예약할 수 있습니다.';
  if (status === 401) return '로그인이 필요합니다.';
  if (data?.message) return data.message;
  return fallback;
}
