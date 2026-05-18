import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { loadAuth, clearAuth } from "./lib/auth";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import UsersPage from "./pages/UsersPage";
import UserDetailPage from "./pages/UserDetailPage";

export default function App() {
  const location = useLocation();
  const isLogin = location.pathname === "/login";
  const authed = !!loadAuth();

  return (
    <div className="min-h-screen bg-neutral-50 text-neutral-900">
      <header className="border-b border-neutral-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-6">
            <Link to="/" className="text-lg font-semibold tracking-tight">
              vibi · admin
            </Link>
            {authed && !isLogin && (
              <nav className="flex items-center gap-4 text-sm text-neutral-600">
                <Link to="/" className="hover:text-neutral-900">Overview</Link>
                <Link to="/users" className="hover:text-neutral-900">Users</Link>
              </nav>
            )}
          </div>
          {authed && !isLogin && (
            <button
              type="button"
              onClick={() => {
                clearAuth();
                window.location.hash = "#/login";
                window.location.reload();
              }}
              className="rounded border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-100"
            >
              Logout
            </button>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<RequireAuth><DashboardPage /></RequireAuth>} />
          <Route path="/users" element={<RequireAuth><UsersPage /></RequireAuth>} />
          <Route path="/users/:id" element={<RequireAuth><UserDetailPage /></RequireAuth>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const authed = !!loadAuth();
  if (!authed) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
