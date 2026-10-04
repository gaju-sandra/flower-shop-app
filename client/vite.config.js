import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // forward API + uploaded images to the Express server in development
    proxy: {
      '/api': 'http://localhost:5000',
      '/uploads': 'http://localhost:5000',
    },
  },
  build: {
    rollupOptions: {
      output: {
        // keep the heavy chart library out of the storefront bundle
        manualChunks: { charts: ['recharts'], vendor: ['react', 'react-dom', 'react-router-dom'] },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    globals: true,
  },
});
