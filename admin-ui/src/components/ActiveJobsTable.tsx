import type { AdminActiveJob } from "../lib/api";
import { formatDurationMs, formatIsoDateTime } from "../lib/format";

/**
 * status='PROCESSING' 잡 목록. 가장 오래된 것 먼저 — stuck 의심 신호.
 * 비어있으면 안내 메시지 (정상 상태 표시).
 */
export default function ActiveJobsTable({ rows }: { rows: AdminActiveJob[] }) {
  if (!rows.length) {
    return (
      <div className="flex h-24 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        진행 중인 잡이 없습니다.
      </div>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="min-w-full divide-y divide-neutral-200 text-sm">
        <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
          <tr>
            <th className="px-4 py-3">Type</th>
            <th className="px-4 py-3">Job ID</th>
            <th className="px-4 py-3">User</th>
            <th className="px-4 py-3 text-right">Duration</th>
            <th className="px-4 py-3">Started</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">
          {rows.map((j) => (
            <tr key={j.jobId} className="hover:bg-neutral-50">
              <td className="px-4 py-3">
                <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
                  {j.jobType}
                </span>
              </td>
              <td className="px-4 py-3 font-mono text-xs">{j.jobId}</td>
              <td className="px-4 py-3 text-neutral-600">{j.userEmail}</td>
              <td className="px-4 py-3 text-right tabular-nums">{formatDurationMs(j.sourceDurationMs)}</td>
              <td className="px-4 py-3 text-neutral-600">{formatIsoDateTime(j.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
