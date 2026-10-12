import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Relative base so assets load correctly from any gateway mount path
  base: './',
  build: {
    outDir: 'dist',
  },
});
