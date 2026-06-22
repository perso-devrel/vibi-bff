import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  adminFetch,
  AdminAuthError,
  AdminActiveJob,
  AdminDailyStats,
  AdminDurationBucket,
  AdminExternalCallDaily,
  AdminJobStatusBreakdown,
  AdminOverview,
  AdminRevenue,
  AdminRevenueDaily,
  AdminSignupDaily,
} from "../lib/api";
import { formatDurationMs } from "../lib/format";
import StatCard from "../components/StatCard";
import DailyChart from "../components/DailyChart";
import HistogramChart from "../components/HistogramChart";
import ExternalCallsTable from "../components/ExternalCallsTable";
import SignupChart from "../components/SignupChart";
import ActiveJobsTable from "../components/ActiveJobsTable";
import RevenuePanel from "../components/RevenuePanel";
import RevenueChart from "../components/RevenueChart";
import JobStatusTable from "../components/JobStatusTable";

interface State {
  overview: AdminOverview | null;
  revenue: AdminRevenue | null;
  revenueDaily: AdminRevenueDaily[];
  jobStatus: AdminJobStatusBreakdown[];
  daily: AdminDailyStats[];
  externalCalls: AdminExternalCallDaily[];
  histogram: AdminDurationBucket[];
  activeJobs: AdminActiveJob[];
  signups: AdminSignupDaily[];
  loading: boolean;
  error: string | null;
}

const INITIAL: State = {
  overview: null, revenue: null, revenueDaily: [], jobStatus: [],
  daily: [], externalCalls: [], histogram: [], activeJobs: [], signups: [],
  loading: true, error: null,
};

export default function DashboardPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<State>(INITIAL);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [overview, revenue, revenueDaily, jobStatus, daily, externalCalls, histogram, activeJobs, signups] =
          await Promise.all([
            adminFetch<AdminOverview>("/api/v2/admin/overview"),
            adminFetch<AdminRevenue>("/api/v2/admin/revenue"),
            adminFetch<AdminRevenueDaily[]>("/api/v2/admin/revenue/daily"),
            adminFetch<AdminJobStatusBreakdown[]>("/api/v2/admin/jobs/status-breakdown"),
            adminFetch<AdminDailyStats[]>("/api/v2/admin/stats/daily"),
            adminFetch<AdminExternalCallDaily[]>("/api/v2/admin/stats/external-calls"),
            adminFetch<AdminDurationBucket[]>("/api/v2/admin/stats/duration-histogram"),
            adminFetch<AdminActiveJob[]>("/api/v2/admin/jobs/active"),
            adminFetch<AdminSignupDaily[]>("/api/v2/admin/stats/signups"),
          ]);
        if (!cancelled) setState({
          overview, revenue, revenueDaily, jobStatus,
          daily, externalCalls, histogram, activeJobs, signups,
          loading: false, error: null,
        });
      } catch (e) {
        if (cancelled) return;
        if (e instanceof AdminAuthError) { navigate("/login", { replace: true }); return; }
        setState((s) => ({ ...s, loading: false, error: e instanceof Error ? e.message : "load failed" }));
      }
    })();
    return () => { cancelled = true; };
  }, [navigate]);

  if (state.loading) return <p className="text-sm text-neutral-500">불러오는 중…</p>;
  if (state.error) return <ErrorBanner message={state.error} />;
  if (!state.overview) return null;

  const o = state.overview;
  return (
    <div className="space-y-10">
      <section>
        <h1 className="text-xl font-semibold">Overview</h1>
        <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-4">
          <StatCard label="Users" value={o.totalUsers.toLocaleString()} />
          <StatCard label="Active (7d)" value={o.activeUsersLast7Days.toLocaleString()} />
          <StatCard label="Renders" value={o.totalRenders.toLocaleString()} />
          <StatCard label="Separations" value={o.totalSeparations.toLocaleString()} />
        </div>
        <div className="mt-4">
          <StatCard
            label="Total uploaded duration"
            value={formatDurationMs(o.totalSourceDurationMs)}
            sub="render 잡 입력 길이 합계 (separation 제외)"
          />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Revenue (IAP)</h2>
        <p className="mt-1 text-sm text-neutral-500">
          크레딧 인앱결제 — 결제자 / 판매 크레딧 / Apple·Google 분포. 금액은 미저장이라 크레딧 수로 표시, admin 지급 제외.
        </p>
        <div className="mt-4">
          {state.revenue ? <RevenuePanel revenue={state.revenue} /> : null}
        </div>
        <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
          <RevenueChart data={state.revenueDaily} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Job success / failure</h2>
        <p className="mt-1 text-sm text-neutral-500">
          render / separation 의 성공·실패·진행중 분해. 성공률은 종료된 잡(성공+실패) 기준.
        </p>
        <div className="mt-4">
          <JobStatusTable rows={state.jobStatus} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">In progress</h2>
        <p className="mt-1 text-sm text-neutral-500">
          현재 PROCESSING 상태인 잡. 오래 머물러 있으면 stuck 의심.
        </p>
        <div className="mt-4">
          <ActiveJobsTable rows={state.activeJobs} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">External API calls</h2>
        <p className="mt-1 text-sm text-neutral-500">
          Perso 호출 카운트 + 실패율 + p95 latency. 비용 추정 + 안정성 모니터.
        </p>
        <div className="mt-4">
          <ExternalCallsTable rows={state.externalCalls} />
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold">Last 30 days</h2>
        <p className="mt-1 text-sm text-neutral-500">일별 render / separation 잡 카운트. UTC 기준.</p>
        <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
          <DailyChart data={state.daily} />
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section>
          <h2 className="text-lg font-semibold">Video duration distribution</h2>
          <p className="mt-1 text-sm text-neutral-500">
            사용자가 올린 render 잡의 입력 영상 길이 분포.
          </p>
          <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
            <HistogramChart data={state.histogram} />
          </div>
        </section>

        <section>
          <h2 className="text-lg font-semibold">New signups (30d)</h2>
          <p className="mt-1 text-sm text-neutral-500">
            일별 신규 가입자 + provider (Google / Apple) 비중.
          </p>
          <div className="mt-4 rounded-lg border border-neutral-200 bg-white p-4">
            <SignupChart data={state.signups} />
          </div>
        </section>
      </div>
    </div>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">
      {message}
    </div>
  );
}
