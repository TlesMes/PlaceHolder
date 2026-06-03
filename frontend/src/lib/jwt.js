// JWT payload를 디코딩한다 (검증 없이 클레임만 읽음 — 서버가 검증 주체).
// 백엔드 토큰 클레임: sub(userId), email, role, exp.
export function decodeJwt(token) {
  if (!token) return null;
  try {
    const payload = token.split('.')[1];
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(json);
  } catch {
    return null;
  }
}

// 토큰 만료 여부 (exp는 초 단위).
export function isTokenExpired(claims) {
  if (!claims?.exp) return true;
  return claims.exp * 1000 <= Date.now();
}
