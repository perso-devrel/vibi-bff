import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwind from "@tailwindcss/vite";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname_ = path.dirname(fileURLToPath(import.meta.url));

/**
 * Admin UI 빌드 산출물은 BFF 의 `src/main/resources/admin/` 으로 직접 떨어뜨려서
 * Ktor `staticResources("/${ADMIN_SLUG}", "admin")` 가 그대로 서빙한다.
 *
 * - `base: "./"` — 모든 asset 을 상대경로로. ADMIN_SLUG 가 런타임 env 라 빌드 타임에 prefix
 *   를 모를 수 있는데, 상대경로면 어떤 prefix 에 마운트되든 동작.
 * - HashRouter 사용 — server 가 SPA fallback 처리 안 해도 # 뒤는 client-side. admin 경로가
 *   BFF access log 에 안 남는 부수효과도 좋음.
 * - tsconfig `paths` 의 `@/*` 를 Vite 도 동일하게 해석하도록 alias 명시.
 * - envDir = vibi-bff 루트 — BFF 의 .env 와 동일 파일 공유. `VITE_*` prefix 만 client 번들에
 *   inline 되므로 BFF 의 다른 비밀(AUTH_JWT_SECRET 등)은 노출 0. admin-ui/.env.local 도
 *   별개 override 로 여전히 동작 (cwd 기준 자동 로드).
 */
export default defineConfig({
  plugins: [react(), tailwind()],
  base: "./",
  envDir: path.resolve(__dirname_, ".."),
  resolve: {
    alias: {
      "@": path.resolve(__dirname_, "./src"),
    },
  },
  build: {
    outDir: path.resolve(__dirname_, "../src/main/resources/admin"),
    emptyOutDir: true,
    sourcemap: true,
  },
});
