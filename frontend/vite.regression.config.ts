import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 独立测试入口，不把测试桩、接口替身或测试页面打包进生产 dist。
export default defineConfig({
  plugins: [react()],
  build: { outDir: 'regression-dist', emptyOutDir: true, rollupOptions: { input: 'tests/chat-drawer-regression.html' } },
});
