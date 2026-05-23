import type { AdminExternalCallDaily } from "../lib/api";

/**
 * Perso/Gemini 외부 호출 일별 표 — 차트보다 표가 더 정보 밀도 높음 (provider × endpoint × date).
 * 빈 응답이면 안내 메시지. 실패율은 callCount=0 이면 표시 안 함.
 */
export default function ExternalCallsTable({ rows }: { rows: AdminExternalCallDaily[] }) {
  if (!rows.length) {
    return (
      <div className="flex h-32 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        선택 기간에 외부 API 호출 기록이 없습니다.
      </div>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="min-w-full divide-y divide-neutral-200 text-sm">
        <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
          <tr>
            <th className="px-4 py-3">Date</th>
            <th className="px-4 py-3">Provider</th>
            <th className="px-4 py-3">Endpoint</th>
            <th className="px-4 py-3 text-right">Calls</th>
            <th className="px-4 py-3 text-right">Failures</th>
            <th className="px-4 py-3 text-right">Fail rate</th>
            <th className="px-4 py-3 text-right">p95 (ms)</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">
          {rows.map((r, i) => {
            const failRate = r.callCount > 0 ? (r.failureCount / r.callCount) * 100 : 0;
            const failRateStyle =
              failRate >= 10 ? "text-rose-700" : failRate >= 1 ? "text-amber-700" : "text-neutral-600";
            return (
              <tr key={`${r.date}-${r.provider}-${r.endpoint}-${i}`} className="hover:bg-neutral-50">
                <td className="px-4 py-3 text-neutral-700">{r.date}</td>
                <td className="px-4 py-3">
                  <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
                    {r.provider}
                  </span>
                </td>
                <td className="px-4 py-3 font-mono text-xs">{r.endpoint}</td>
                <td className="px-4 py-3 text-right tabular-nums">{r.callCount.toLocaleString()}</td>
                <td className="px-4 py-3 text-right tabular-nums">{r.failureCount.toLocaleString()}</td>
                <td className={`px-4 py-3 text-right tabular-nums ${failRateStyle}`}>
                  {r.callCount > 0 ? `${failRate.toFixed(1)}%` : "-"}
                </td>
                <td className="px-4 py-3 text-right tabular-nums text-neutral-600">
                  {r.p95LatencyMs.toLocaleString()}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
