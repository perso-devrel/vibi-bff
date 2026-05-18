import { loadAuth, clearAuth } from "./auth";

/**
 * BFF API fetch wrapper. Authorization 헤더 자동 부착. 401/403 시 토큰 비우고 throw —
 * caller (대부분 ProtectedRoute) 가 로그인 페이지로 redirect.
 *
 * baseUrl 은 same-origin (BFF 가 admin UI 서빙) 이라 빈 prefix. dev 모드 (vite dev server)
 * 에서 BFF 가 다른 origin 일 때는 VITE_BFF_BASE_URL 로 override.
 */
const BASE_URL = (import.meta.env.VITE_BFF_BASE_URL ?? "").replace(/\/+$/, "");

export class AdminAuthError extends Error {
  constructor(public readonly code: "missing_token" | "unauthorized" | "forbidden") {
    super(code);
    this.name = "AdminAuthError";
  }
}

export async function adminFetch<T>(path: string): Promise<T> {
  const auth = loadAuth();
  if (!auth) throw new AdminAuthError("missing_token");

  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    cache: "no-store",
  });
  if (res.status === 401) {
    clearAuth();
    throw new AdminAuthError("unauthorized");
  }
  if (res.status === 403) {
    throw new AdminAuthError("forbidden");
  }
  if (!res.ok) {
    throw new Error(`BFF ${path} returned ${res.status}`);
  }
  return (await res.json()) as T;
}

/** Google ID Token → BFF JWT 교환. 응답 그대로 — caller 가 role 확인 후 저장. */
export async function exchangeGoogleIdToken(idToken: string): Promise<{
  accessToken: string;
  expiresAt: number;
  user: { sub: string; email: string; name: string; role?: string };
}> {
  const res = await fetch(`${BASE_URL}/api/v2/auth/google`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }),
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`google exchange failed (${res.status})`);
  }
  return res.json();
}

// ── 응답 DTO (BFF AdminModels.kt 와 1:1) ───────────────────────────────────
export interface AdminOverview {
  totalUsers: number;
  totalRenders: number;
  totalSeparations: number;
  totalSourceDurationMs: number;
  activeUsersLast7Days: number;
}

export interface AdminDailyStats {
  date: string;
  renderCount: number;
  separationCount: number;
  totalSourceDurationMs: number;
}

export interface AdminUserOverview {
  userId: string;
  email: string;
  name: string;
  role: string;
  totalRenders: number;
  totalSeparations: number;
  totalSourceDurationMs: number;
  lastActivityAt: string;
}

export interface AdminUsersResponse {
  users: AdminUserOverview[];
  total: number;
}

export interface AdminUserJob {
  jobId: string;
  status: string;
  sourceDurationMs: number;
  createdAt: string;
  finishedAt: string | null;
  separationCount: number;
}

export interface AdminUserJobsResponse {
  jobs: AdminUserJob[];
  total: number;
}

export interface AdminExternalCallDaily {
  date: string;
  provider: string;
  endpoint: string;
  callCount: number;
  failureCount: number;
  p95LatencyMs: number;
}

export interface AdminDurationBucket {
  bucket: string;
  count: number;
}

export interface AdminActiveJob {
  jobType: string;
  jobId: string;
  userEmail: string;
  sourceDurationMs: number;
  createdAt: string;
}

export interface AdminSignupDaily {
  date: string;
  googleCount: number;
  appleCount: number;
}
