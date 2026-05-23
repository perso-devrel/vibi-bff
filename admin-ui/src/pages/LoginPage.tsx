import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { exchangeGoogleIdToken } from "../lib/api";
import { saveAuth, decodeRole } from "../lib/auth";

declare global {
  interface Window {
    google?: {
      accounts?: {
        id?: {
          initialize: (opts: {
            client_id: string;
            callback: (response: { credential: string }) => void;
          }) => void;
          renderButton: (
            parent: HTMLElement,
            opts: { theme?: string; size?: string; width?: number; shape?: string }
          ) => void;
        };
      };
    };
  }
}

const GSI_SRC = "https://accounts.google.com/gsi/client";

export default function LoginPage() {
  const navigate = useNavigate();
  const clientId = import.meta.env.VITE_GOOGLE_OAUTH_CLIENT_ID_ADMIN as string | undefined;
  const buttonRef = useRef<HTMLDivElement>(null);
  const [gsiReady, setGsiReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  // Google Identity Services script 한 번만 로드.
  useEffect(() => {
    if (document.querySelector(`script[src="${GSI_SRC}"]`)) {
      setGsiReady(true);
      return;
    }
    const s = document.createElement("script");
    s.src = GSI_SRC;
    s.async = true;
    s.defer = true;
    s.onload = () => setGsiReady(true);
    document.head.appendChild(s);
  }, []);

  useEffect(() => {
    if (!gsiReady || !clientId || !buttonRef.current || !window.google?.accounts?.id) return;
    window.google.accounts.id.initialize({
      client_id: clientId,
      callback: async (response) => {
        setPending(true);
        setError(null);
        try {
          const result = await exchangeGoogleIdToken(response.credential);
          const role = result.user.role ?? decodeRole(result.accessToken);
          if (role !== "admin") {
            setError("admin 권한이 없는 계정입니다.");
            return;
          }
          saveAuth({ token: result.accessToken, expiresAt: result.expiresAt });
          navigate("/", { replace: true });
        } catch (e) {
          setError(e instanceof Error ? e.message : "login error");
        } finally {
          setPending(false);
        }
      },
    });
    window.google.accounts.id.renderButton(buttonRef.current, {
      theme: "outline",
      size: "large",
      shape: "pill",
      width: 280,
    });
  }, [gsiReady, clientId, navigate]);

  return (
    <div className="relative min-h-screen overflow-hidden bg-neutral-50 text-neutral-900">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(60rem_30rem_at_50%_-10%,rgba(99,102,241,0.12),transparent),radial-gradient(40rem_25rem_at_90%_110%,rgba(236,72,153,0.10),transparent)]"
      />
      <div className="relative flex min-h-screen items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex flex-col items-center text-center">
            <div className="mb-4 inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-neutral-900 text-base font-semibold tracking-tight text-white shadow-sm">
              vb
            </div>
            <h1 className="text-2xl font-semibold tracking-tight">vibi · admin</h1>
            <p className="mt-1.5 text-sm text-neutral-500">영상은 남기고, 소음만 지운다</p>
          </div>

          {!clientId ? (
            <div className="text-center text-sm">
              <p className="font-medium text-rose-700">설정 누락</p>
              <p className="mt-2 text-neutral-600">
                <code className="rounded bg-neutral-100 px-1.5 py-0.5 font-mono text-xs">
                  VITE_GOOGLE_OAUTH_CLIENT_ID_ADMIN
                </code>{" "}
                빌드 env 가 없습니다.
              </p>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-3">
              <div
                ref={buttonRef}
                aria-busy={pending}
                className="flex min-h-[44px] items-center justify-center"
              />
              {!gsiReady && (
                <p className="text-xs text-neutral-400">Google 로그인 로드 중…</p>
              )}
              {pending && <p className="text-xs text-neutral-500">로그인 처리 중…</p>}
              {error && (
                <p className="w-full rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
                  {error}
                </p>
              )}
            </div>
          )}

          <p className="mt-6 text-center text-xs text-neutral-400">
            인증된 세션은 토큰 만료 시까지 유효합니다.
          </p>
        </div>
      </div>
    </div>
  );
}
