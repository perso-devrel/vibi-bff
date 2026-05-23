/**
 * Admin JWT 저장소. sessionStorage 사용 — 탭 닫으면 만료. admin 답게 보수적으로 짧게.
 *
 * JWT 자체엔 `role` 클레임이 박혀있지만 client 측에선 그걸 신뢰하지 않는다 — 모든 권한
 * 결정은 BFF `requireAdmin` 이 같은 JWT 를 다시 검증하면서 한다. 본 모듈은 토큰 보관/만료
 * 시점 추적만 담당.
 */
const TOKEN_KEY = "vibi.admin.token";
const EXPIRES_KEY = "vibi.admin.expiresAt";

export interface StoredAdminAuth {
  token: string;
  expiresAt: number; // epoch ms
}

export function loadAuth(): StoredAdminAuth | null {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const exp = Number(sessionStorage.getItem(EXPIRES_KEY) ?? "0");
  if (!token || !exp || exp <= Date.now()) return null;
  return { token, expiresAt: exp };
}

export function saveAuth(auth: StoredAdminAuth): void {
  sessionStorage.setItem(TOKEN_KEY, auth.token);
  sessionStorage.setItem(EXPIRES_KEY, String(auth.expiresAt));
}

export function clearAuth(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(EXPIRES_KEY);
}

/** JWT payload 의 role 클레임을 파싱 — UI 분기 (예: admin 아니면 즉시 로그아웃) 용도. */
export function decodeRole(token: string): string | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const obj = JSON.parse(json) as { role?: string };
    return obj.role ?? null;
  } catch {
    return null;
  }
}
