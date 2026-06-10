// 테마(라이트/다크) 읽기·쓰기 유틸. localStorage 영속 + 시스템 설정 fallback.
const THEME_KEY = 'theme';

export function getInitialTheme() {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
    if (window.matchMedia('(prefers-color-scheme: dark)').matches) return 'dark';
  } catch {
    // localStorage 접근 불가(시크릿 등) → 기본 라이트
  }
  return 'light';
}

// html.dark 클래스 토글 + 선택 영속. index.html 인라인 스크립트와 동일 규칙.
export function applyTheme(theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark');
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    // 저장 실패는 무시(런타임 토글은 여전히 동작)
  }
}
