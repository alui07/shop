import tailwindcss from '@tailwindcss/vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const underVitest = process.env['VITEST'] !== undefined;

const apiProxy = {
  target: process.env['API_PROXY_TARGET'] ?? 'http://localhost:8080',
  changeOrigin: true,
};

export default defineConfig({
  plugins: [
    ...(underVitest ? [] : [tanstackRouter({ target: 'react', autoCodeSplitting: true })]),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': apiProxy,
      '/oauth2/': apiProxy,
      '/login/oauth2/': apiProxy,
      '^/logout$': apiProxy,
    },
  },
  test: {
    environment: 'jsdom',
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/routeTree.gen.ts', 'src/components/ui/**'],
      reporter: ['text', 'html'],
      reportsDirectory: './coverage',
      thresholds: { lines: 100, branches: 100, functions: 100, statements: 100 },
    },
  },
});
