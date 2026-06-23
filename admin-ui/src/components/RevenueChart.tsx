import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AdminRevenueDaily } from "../lib/api";

/**
 * 일별 판매 크레딧 — Apple/Google 스택 area. admin-grant 제외 (수익만).
 */
export default function RevenueChart({ data }: { data: AdminRevenueDaily[] }) {
  const total = data.reduce((acc, d) => acc + d.appleCredits + d.googleCredits, 0);
  if (total === 0) {
    return (
      <div className="flex h-48 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        선택 기간에 결제 내역이 없습니다.
      </div>
    );
  }
  const rows = data.map((d) => ({ date: d.date.slice(5), Apple: d.appleCredits, Google: d.googleCredits }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={rows} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
          <XAxis dataKey="date" stroke="#737373" fontSize={12} />
          <YAxis stroke="#737373" fontSize={12} allowDecimals={false} />
          <Tooltip
            contentStyle={{
              backgroundColor: "white",
              border: "1px solid #e5e5e5",
              borderRadius: 8,
              fontSize: 12,
            }}
          />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Area type="monotone" dataKey="Apple" stackId="1" stroke="#171717" fill="#171717" fillOpacity={0.6} />
          <Area type="monotone" dataKey="Google" stackId="1" stroke="#3b82f6" fill="#3b82f6" fillOpacity={0.6} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
