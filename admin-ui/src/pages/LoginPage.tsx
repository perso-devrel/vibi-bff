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
            opts: { theme?: string; size?: string; width?: number }
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
      width: 320,
    });
  }, [gsiReady, clientId, navigate]);

  if (!clientId) {
    return (
      <div className="mx-auto max-w-md py-16">
        <h1 className="text-2xl font-semibold">Admin login</h1>
        <p className="mt-4 text-sm text-rose-600">
          <code>VITE_GOOGLE_OAUTH_CLIENT_ID_ADMIN</code> 빌드 env 가 없습니다.
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-md py-16">
      <h1 className="text-2xl font-semibold">Admin login</h1>
      <p className="mt-2 text-sm text-neutral-600">
        Google 계정으로 로그인. role=admin 계정만 통과합니다.
      </p>
      <div ref={buttonRef} aria-busy={pending} className="mt-8" />
      {pending && <p className="mt-3 text-sm text-neutral-500">로그인 처리 중…</p>}
      {error && <p className="mt-3 text-sm text-rose-600">{error}</p>}
    </div>
  );
}
