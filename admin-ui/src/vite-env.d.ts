/// <reference types="vite/client" />

/**
 * Vite 가 빌드 타임에 주입하는 import.meta.env 의 타입 선언.
 *
 * Vite 표준 prefix `VITE_` 키만 client 번들로 노출 — 그 외 키는 빌드 시 undefined.
 * 모두 optional 로 선언 — env 누락 시 코드가 fallback ("dev" / "0.1" 등) 으로 동작하도록.
 */
interface ImportMetaEnv {
  /** 동일 origin (BFF 가 admin SPA 서빙) 이면 비워둠. dev 모드 vite dev server 가 다른 origin 일 때만 설정. */
  readonly VITE_BFF_BASE_URL?: string;

  /** Google Cloud Console > OAuth 2.0 Client ID (**Web application** 타입). */
  readonly VITE_GOOGLE_OAUTH_CLIENT_ID_ADMIN?: string;

  /** 운영 모니터링 — blank 면 Sentry SDK init skip (no-op 모드). */
  readonly VITE_SENTRY_DSN_ADMIN?: string;
  readonly VITE_SENTRY_ENV?: string;
  readonly VITE_SENTRY_TRACES_SAMPLE_RATE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
