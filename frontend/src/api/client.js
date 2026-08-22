import axios from 'axios';

const client = axios.create({
  baseURL: 'http://localhost:8080',
});

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 로그인·회원가입 요청의 401은 "자격 증명이 틀렸다"는 뜻이지 세션이 끊긴 게 아니다.
// 여기서 토큰을 지우고 리다이렉트하면 사용자가 에러 메시지를 볼 새도 없이 화면이 갈아엎힌다.
const CREDENTIAL_PATHS = ['/api/auth/login', '/api/auth/signup'];

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const url = error.config?.url ?? '';
    const isCredentialCheck = CREDENTIAL_PATHS.some((path) => url.includes(path));

    // 서버가 인증을 거부했는데 화면만 로그인 상태로 남아 있는 것을 막는다.
    // AuthContext는 마운트 시점에 만료(exp)만 검사하므로 이 경로들을 못 잡는다:
    // 페이지를 열어둔 채 토큰 만료 / soft delete된 사용자 / JWT 시크릿 교체.
    if (status === 401 && !isCredentialCheck) {
      localStorage.removeItem('accessToken');
      // 하드 내비게이션이라 AuthContext가 토큰 없는 상태로 다시 읽힌다.
      // 이미 로그인 화면이면 그대로 둔다 — 되풀이 이동을 막는다.
      if (window.location.pathname !== '/login') {
        window.location.replace('/login');
      }
    }
    return Promise.reject(error);
  }
);

export default client;
