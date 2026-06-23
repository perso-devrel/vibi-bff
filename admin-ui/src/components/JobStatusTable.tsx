import type { AdminJobStatusBreakdown } from "../lib/api";

/**
 * 잡 성공/실패 분해 — render/separation 각 행. 성공률은 terminal(succeeded+failed) 기준으로
 * 여기서 계산해 in-progress 가 분모를 흐리지 않게 한다. ExternalCallsTable 과 동일한 임계 색상.
 */
export default function JobStatusTable({ rows }: { rows: AdminJobStatusBreakdown[] }) {
  if (!rows.length) {
    return (
      <div className="flex h-32 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        아직 잡 데이터가 없습니다.
      </div>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="min-w-full divide-y divide-neutral-200 text-sm">
        <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
          <tr>
            <th className="px-4 py-3">Job</th>
            <th className="px-4 py-3 text-right">Total</th>
            <th className="px-4 py-3 text-right">Succeeded</th>
            <th className="px-4 py-3 text-right">Failed</th>
            <th className="px-4 py-3 text-right">In progress</th>
            <th className="px-4 py-3 text-right">Success rate</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">
          {rows.map((r) => {
            const terminal = r.succeeded + r.failed;
            const successRate = terminal > 0 ? (r.succeeded / terminal) * 100 : 0;
            // 성공률이 낮을수록 강조 (ExternalCallsTable 의 실패율 색상과 대칭).
            const rateStyle =
              terminal === 0
                ? "text-neutral-400"
                : successRate < 90
                  ? "text-rose-700"
                  : successRate < 99
                    ? "text-amber-700"
                    : "text-emerald-700";
            return (
              <tr key={r.jobType} className="hover:bg-neutral-50">
                <td className="px-4 py-3">
                  <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
                    {r.jobType}
                  </span>
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{r.total.toLocaleString()}</td>
                <td className="px-4 py-3 text-right tabular-nums text-neutral-700">{r.succeeded.toLocaleString()}</td>
                <td className="px-4 py-3 text-right tabular-nums text-neutral-700">{r.failed.toLocaleString()}</td>
                <td className="px-4 py-3 text-right tabular-nums text-neutral-500">{r.inProgress.toLocaleString()}</td>
                <td className={`px-4 py-3 text-right tabular-nums ${rateStyle}`}>
                  {terminal > 0 ? `${successRate.toFixed(1)}%` : "-"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
