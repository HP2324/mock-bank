import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // `npm run dev` serves the UI with hot reload; API and session routes go to Spring Boot.
    proxy: {
      '/api': backend,
      '/logout': backend,
      '/debug': backend,
    },
  },
  test: {
    environment: 'jsdom',
  },
});
