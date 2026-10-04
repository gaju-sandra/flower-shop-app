/** Floral palette: rose, crimson, leaf green, cream and lilac. */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        rose: {
          50: '#fff5f7', 100: '#ffe4ea', 200: '#fecdd8', 300: '#fda4b8', 400: '#f97393',
          500: '#ef4770', 600: '#d9265a', 700: '#b6194a', 800: '#981944', 900: '#811940',
        },
        crimson: { 500: '#dc2647', 600: '#be123c' },
        leaf: { 50: '#f1f8f2', 100: '#ddeedf', 200: '#bcdcc1', 400: '#6aae78', 500: '#4a925a', 600: '#387646', 700: '#2e5e3a' },
        cream: { 50: '#fffdf8', 100: '#fdf8ee', 200: '#f8eedb' },
        lilac: { 50: '#faf7fd', 100: '#f3edfa', 200: '#e6dcf5', 300: '#d2c0ec', 400: '#b89ade', 500: '#9b74cc', 600: '#8159b3' },
        ink: { 400: '#8a7f86', 500: '#6b6168', 600: '#544b51', 700: '#3f373c', 900: '#241e22' },
      },
      fontFamily: {
        display: ['"Playfair Display"', 'Georgia', 'serif'],
        sans: ['Poppins', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        soft: '0 4px 24px -6px rgba(185, 25, 74, 0.12)',
        card: '0 10px 40px -12px rgba(129, 25, 64, 0.22)',
      },
      keyframes: {
        'fade-up': { '0%': { opacity: 0, transform: 'translateY(16px)' }, '100%': { opacity: 1, transform: 'none' } },
        'ken-burns': { '0%': { transform: 'scale(1)' }, '100%': { transform: 'scale(1.08)' } },
        float: { '0%,100%': { transform: 'translateY(0)' }, '50%': { transform: 'translateY(-8px)' } },
        pop: { '0%': { transform: 'scale(1)' }, '50%': { transform: 'scale(1.3)' }, '100%': { transform: 'scale(1)' } },
      },
      animation: {
        'fade-up': 'fade-up .6s ease-out both',
        'ken-burns': 'ken-burns 7s ease-out both',
        float: 'float 4s ease-in-out infinite',
        pop: 'pop .35s ease-out',
      },
    },
  },
  plugins: [],
};
