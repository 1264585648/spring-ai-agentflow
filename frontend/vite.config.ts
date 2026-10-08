import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 对 SSE 长连接至关重要，防止中间层代理对响应流进行缓冲截断
        ws: false,
      },
    },
  },
});
