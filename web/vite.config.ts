import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Everything under /api and /actuator goes to Spring in development.
    //
    // The point is not convenience. Proxying makes the browser see one origin,
    // so the session cookie is a first-party cookie and behaves in development
    // exactly as it will in production behind one host. Pointing the client
    // straight at :8080 instead would mean fighting CORS and SameSite for a
    // problem that will not exist once deployed.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
