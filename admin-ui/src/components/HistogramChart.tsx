import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AdminDurationBucket } from "../lib/api";

/**
 * 영상 길이 분포 5-bucket 히스토그램. BFF 가 항상 5칸 (0 행 포함) 반환하므로 그대로 mapping.
 * 전체 카운트가 0 이면 안내 메시지로 fallback (placeholder 차트 안 그림).
 */
export default function HistogramChart({ data }: { data: AdminDurationBucket[] }) {
  const total = data.reduce((acc, d) => acc + d.count, 0);
  if (total === 0) {
    return (
      <div className="flex h-48 items-center justify-center rounded-lg border border-dashed border-neutral-300 text-sm text-neutral-500">
        아직 render 잡이 없습니다.
      </div>
    );
  }
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
          <XAxis dataKey="bucket" stroke="#737373" fontSize={12} />
          <YAxis stroke="#737373" fontSize={12} allowDecimals={false} />
          <Tooltip
            contentStyle={{
              backgroundColor: "white",
              border: "1px solid #e5e5e5",
              borderRadius: 8,
              fontSize: 12,
            }}
          />
          <Bar dataKey="count" name="Renders" fill="#6366f1" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
