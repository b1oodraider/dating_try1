import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // '' prefix: read every variable from .env files and process.env (VITE_API_TARGET is build-time only).
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = {
    '/api': {
      target: env.VITE_API_TARGET || 'http://localhost:8080',
      changeOrigin: true,
    },
  };

  return {
    plugins: [react(), tailwindcss()],
    server: {
      // `npm run dev`: the SPA talks to the Java backend through this proxy (same origin, no CORS).
      proxy,
    },
    preview: {
      proxy,
    },
  };
});
