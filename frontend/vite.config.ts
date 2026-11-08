import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwind from '@tailwindcss/vite'

const proxy = { '/api': 'http://127.0.0.1:8080' }

export default defineConfig({
  plugins: [react(), tailwind()],
  server: { host: '127.0.0.1', proxy },
  preview: { host: '127.0.0.1', proxy },
})
