import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // sockjs-client's browser-crypto module reads a bare `global.crypto`. The
  // published CommonJS bundle wraps every module in a `(function(global){...})`
  // shim, but Vite pre-bundles the ESM entry instead, where that shim is absent —
  // so the app throws `global is not defined` and renders nothing at all. Every
  // test we had passed while the browser was completely blank.
  define: { global: 'globalThis' },
  server: {
    // Listen on every interface, not just loopback: the default is 127.0.0.1,
    // which makes the dev server unreachable from a phone on the same network.
    // Vite 8 allows any IP address in the Host header, so a LAN client hitting
    // http://<this-machine-ip>:5173 needs no allowedHosts entry. The /api and /ws
    // proxies below are resolved on this machine, so they still point at localhost.
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
