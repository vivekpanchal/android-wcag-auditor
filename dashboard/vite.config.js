import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Vitest's transform pipeline doesn't pick up @vitejs/plugin-react's oxc-based
  // automatic JSX runtime config, so it falls back to esbuild's classic transform
  // (which needs `React` in scope) unless told explicitly. Scoped to `test` only
  // so it doesn't fight with the oxc config vite build/dev actually uses.
  ...(process.env.VITEST ? { esbuild: { jsx: 'automatic' } } : {}),
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
  },
})
