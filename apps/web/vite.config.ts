import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/actuator/health': 'http://127.0.0.1:8080',
    },
  },
  preview: {
    port: 4173,
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    exclude: ['e2e/**'],
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    restoreMocks: true,
    clearMocks: true,
    css: true,
  },
});
