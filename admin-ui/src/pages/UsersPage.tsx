import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { adminFetch, AdminAuthError, AdminUsersResponse } from "../lib/api";
import { formatDurationMs, formatIsoDateTime } from "../lib/format";

const PAGE_SIZE = 50;

export default function UsersPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const offset = Math.max(0, Number.parseInt(params.get("offset") ?? "0", 10) || 0);
  const queryFromUrl = params.get("q") ?? "";

  // 검색 input — 사용자가 타이핑 중인 raw 값. URL/요청에는 debounce 적용.
  const [queryDraft, setQueryDraft] = useState(queryFromUrl);
  const [data, setData] = useState<AdminUsersResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 250ms debounce — 빠른 타이핑 시 매 키스트로크에 fetch 안 함.
  useEffect(() => {
    if (queryDraft === queryFromUrl) return;
    const id = window.setTimeout(() => {
      const next = new URLSearchParams(params);
      if (queryDraft.trim()) next.set("q", queryDraft.trim());
      else next.delete("q");
      next.delete("offset");
      setParams(next, { replace: true });
    }, 250);
    return () => window.clearTimeout(id);
  }, [queryDraft, queryFromUrl, params, setParams]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const qs = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
        if (queryFromUrl) qs.set("q", queryFromUrl);
        const res = await adminFetch<AdminUsersResponse>(`/api/v2/admin/users?${qs.toString()}`);
        if (!cancelled) setData(res);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setError(e instanceof Error ? e.message : "load failed");
      }
    })();
    return () => { cancelled = true; };
  }, [offset, queryFromUrl, navigate]);

  if (error) return <p className="text-sm text-rose-600">{error}</p>;
  if (!data) return <p className="text-sm text-neutral-500">불러오는 중…</p>;

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const setOffset = (next: number) => {
    const np = new URLSearchParams(params);
    np.set("offset", String(Math.max(0, next)));
    setParams(np);
  };

  return (
    <div className="space-y-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold">Users</h1>
        <span className="text-sm text-neutral-500">총 {data.total.toLocaleString()}명</span>
      </header>

      <div>
        <input
          type="search"
          value={queryDraft}
          onChange={(e) => setQueryDraft(e.target.value)}
          placeholder="이메일 또는 이름으로 검색…"
          className="w-full max-w-md rounded border border-neutral-300 bg-white px-3 py-2 text-sm placeholder:text-neutral-400 focus:border-blue-500 focus:outline-none"
        />
      </div>

      {data.users.length === 0 ? (
        <p className="rounded-lg border border-dashed border-neutral-300 p-8 text-center text-sm text-neutral-500">
          {queryFromUrl ? "검색 결과가 없습니다." : "아직 가입한 사용자가 없습니다."}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
          <table className="min-w-full divide-y divide-neutral-200 text-sm">
            <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
              <tr>
                <th className="px-4 py-3">User</th>
                <th className="px-4 py-3">Role</th>
                <th className="px-4 py-3 text-right">Renders</th>
                <th className="px-4 py-3 text-right">Separations</th>
                <th className="px-4 py-3 text-right">Uploaded</th>
                <th className="px-4 py-3">Last activity</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {data.users.map((u) => (
                <tr key={u.userId} className="hover:bg-neutral-50">
                  <td className="px-4 py-3">
                    <Link to={`/users/${u.userId}`} className="text-blue-600 hover:underline">
                      {u.name || u.email}
                    </Link>
                    <div className="text-xs text-neutral-500">{u.email}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={
                        u.role === "admin"
                          ? "rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                          : "text-xs text-neutral-500"
                      }
                    >
                      {u.role}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{u.totalRenders.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{u.totalSeparations.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{formatDurationMs(u.totalSourceDurationMs)}</td>
                  <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(u.lastActivityAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <nav className="flex items-center justify-between text-sm">
          <div className="text-neutral-500">Page {currentPage} / {totalPages}</div>
          <div className="flex gap-2">
            {offset > 0 && (
              <button
                onClick={() => setOffset(offset - PAGE_SIZE)}
                className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100"
              >
                Prev
              </button>
            )}
            {currentPage < totalPages && (
              <button
                onClick={() => setOffset(offset + PAGE_SIZE)}
                className="rounded border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100"
              >
                Next
              </button>
            )}
          </div>
        </nav>
      )}
    </div>
  );
}
