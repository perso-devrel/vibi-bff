import type { AdminRevenue } from "../lib/api";
import StatCard from "./StatCard";

/**
 * 수익/IAP 요약 — 결제자 수 + 판매 크레딧 + platform(Apple/Google) 분포.
 *
 * BFF 는 영수증 화폐 금액을 저장하지 않으므로 매출은 "판매된 크레딧 수" 로 표시한다.
 * admin-grant 는 매출이 아니므로 KPI 에서 제외하고 하단에 참고값으로만 노출.
 */
export default function RevenuePanel({ revenue }: { revenue: AdminRevenue }) {
  const r = revenue;
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <StatCard label="Paying users" value={r.payingUsers.toLocaleString()} sub="실결제 1건 이상" />
        <StatCard
          label="Credits sold"
          value={r.creditsSold.toLocaleString()}
          sub={`최근 30일 +${r.creditsSold30d.toLocaleString()}`}
        />
        <StatCard
          label="Purchases"
          value={r.purchaseCount.toLocaleString()}
          sub={`최근 30일 +${r.purchaseCount30d.toLocaleString()}`}
        />
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="min-w-full divide-y divide-neutral-200 text-sm">
          <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
            <tr>
              <th className="px-4 py-3">Platform</th>
              <th className="px-4 py-3 text-right">Purchases</th>
              <th className="px-4 py-3 text-right">Credits</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-100">
            <PlatformRow label="Apple" purchases={r.applePurchaseCount} credits={r.appleCredits} />
            <PlatformRow label="Google" purchases={r.googlePurchaseCount} credits={r.googleCredits} />
          </tbody>
        </table>
      </div>

      {r.adminGrantedCredits > 0 && (
        <p className="text-xs text-neutral-500">
          참고: admin 수동 지급 크레딧 {r.adminGrantedCredits.toLocaleString()}개 (매출 집계 제외).
        </p>
      )}
    </div>
  );
}

function PlatformRow({
  label,
  purchases,
  credits,
}: {
  label: string;
  purchases: number;
  credits: number;
}) {
  return (
    <tr className="hover:bg-neutral-50">
      <td className="px-4 py-3">
        <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">{label}</span>
      </td>
      <td className="px-4 py-3 text-right tabular-nums">{purchases.toLocaleString()}</td>
      <td className="px-4 py-3 text-right tabular-nums">{credits.toLocaleString()}</td>
    </tr>
  );
}
