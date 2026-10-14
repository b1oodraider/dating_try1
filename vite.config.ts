import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';

const rootDir = path.resolve(fileURLToPath(new URL('.', import.meta.url)));

export default defineConfig(({ mode }) => {
  // '' prefix: read every variable from .env files and process.env (VITE_API_TARGET is build-time only).
  const env = loadEnv(mode, rootDir, '');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080';
  const proxy = {
    '/api': {
      target: apiTarget,
      changeOrigin: true,
    },
  };

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': rootDir,
      },
    },
    server: {
      // `npm run dev`: the SPA talks to the Java backend through this proxy (same origin, no CORS).
      proxy,
    },
    preview: {
      proxy,
    },
  };
});
