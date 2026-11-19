import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 백엔드(8080)를 같은 출처로 보이게 프록시한다 — /api/admin/... → http://localhost:8080/admin/...
// 피드 사진(/feed-photos/...)도 같은 길로 받는다. 백엔드 주소가 다르면 ADMIN_API_TARGET 으로 바꾼다.
const target = process.env.ADMIN_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 3D 시범 장면(frontend/components/exercise/mannequin)을 앱과 같은 파일로 쓴다 — 저장소 루트까지 서빙 허용
    fs: { allow: ['..'] },
    proxy: {
      '/api': { target, changeOrigin: true, rewrite: (p) => p.replace(/^\/api/, '') },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': { target, changeOrigin: true, rewrite: (p) => p.replace(/^\/api/, '') },
    },
  },
});
