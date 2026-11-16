import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';

// In dev, /api calls are forwarded to the Spring Boot backend on port 8080.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: BACKEND,
        configure: (proxy) => {
          // Backend not running -> clear message in the UI instead of a bare 500
          proxy.on('error', (err, req, res) => {
            if (!res.headersSent) {
              res.writeHead(503, { 'Content-Type': 'application/json' });
            }
            res.end(
              JSON.stringify({
                message: `Backend is not running on ${BACKEND}. Start it with: cd C:\\FCI\\TableApp\\backend ; mvn spring-boot:run`,
              })
            );
          });
        },
      },
    },
  },
});
