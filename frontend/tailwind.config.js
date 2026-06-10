/** @type {import('tailwindcss').Config} */
const token = (name) => `rgb(var(--${name}) / <alpha-value>)`;

export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // 표면 / 텍스트 / 테두리
        base: token('base'),
        surface: token('surface'),
        'surface-muted': token('surface-muted'),
        border: token('border'),
        fg: token('fg'),
        'fg-muted': token('fg-muted'),
        'fg-subtle': token('fg-subtle'),
        // 브랜드(primary)
        primary: token('primary'),
        'primary-hover': token('primary-hover'),
        'primary-soft': token('primary-soft'),
        'primary-soft-fg': token('primary-soft-fg'),
        // 상태색
        success: token('success'),
        'success-soft': token('success-soft'),
        'success-soft-fg': token('success-soft-fg'),
        warning: token('warning'),
        'warning-soft': token('warning-soft'),
        'warning-soft-fg': token('warning-soft-fg'),
        danger: token('danger'),
        'danger-soft': token('danger-soft'),
        'danger-soft-fg': token('danger-soft-fg'),
      },
      fontFamily: {
        sans: ['Pretendard', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      keyframes: {
        'toast-in': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'toast-in': 'toast-in 0.2s ease-out',
      },
    },
  },
  plugins: [],
};
