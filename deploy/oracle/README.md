# vibi-bff — Oracle Cloud Always Free 배포

Cloud Run → Oracle Ampere(ARM) VM 이전. 앱 코드/Dockerfile 변경 없음. Cloud Run 이
대신 해주던 **상시기동·TLS·재시작·방화벽**만 이 디렉터리에서 직접 wire.

외부 의존성(Neon Postgres / Cloudflare R2 / Perso)은 **그대로 재사용** — 컴퓨트만 바뀜.

```
docker-compose.yml   bff(Ktor) + caddy(자동 HTTPS) 2 컨테이너, 둘 다 restart: always
Caddyfile            도메인 → bff:8080 리버스 프록시, Let's Encrypt 자동 TLS
bff.env.example      VM 런타임 env 템플릿 → bff.env 로 복사해 채움 (gitignored)
setup-firewall.sh    함정1 방지 — VM iptables 80/443 영구 허용
```

---

## 두 함정, 영구 차단

### 함정 1 — iptables (방화벽 이중 구조)
바깥 겹(콘솔 Security List)만 열고 안쪽 겹(VM iptables)을 안 열어 접속 실패하는 케이스.
**두 겹 다** 열어야 함:

1. **콘솔**: VCN → Security List → Ingress Rules 추가
   - Source `0.0.0.0/0`, IP Protocol TCP, Destination Port `80`, `443`
2. **VM 내부**: `./setup-firewall.sh` 실행 (iptables 삽입 + `netfilter-persistent save` 로 영구화)

### 함정 2 — Always Free 인스턴스 회수
순수 Always-Free 계정의 **유휴 ARM 인스턴스**만 회수 대상.
**Pay As You Go(PAYG)로 업그레이드하면 회수 정책 면제 — 비용은 여전히 $0** (Always Free 한도 내 무과금).
운영 서비스면 필수: 콘솔 → Billing → Upgrade to Pay As You Go (결제수단 등록).
`restart: always` 는 reboot/크래시 복구용 안전망 (회수 자체를 막지는 못함 → PAYG 가 본 해법).

---

## 셋업 순서

1. **인스턴스 생성** — Ampere A1 (Always Free), Ubuntu 22.04, public IP 할당
2. **PAYG 업그레이드** (함정 2)
3. **콘솔 Security List 80/443 오픈** (함정 1 바깥 겹)
4. **도메인 A 레코드** → VM public IP (TLS 자동 발급에 필요)
5. SSH 접속 후:
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 iptables-persistent git
   sudo usermod -aG docker $USER && newgrp docker
   git clone <repo> vibi-bff && cd vibi-bff/deploy/oracle
   ./setup-firewall.sh                      # 함정 1 안쪽 겹
   cp bff.env.example bff.env && vim bff.env # 시크릿 채우기 (Cloud Run 값 그대로)
   vim Caddyfile                            # YOUR_EMAIL / api.example.com 교체
   docker compose up -d --build             # 첫 빌드 ~5분 (ARM 네이티브)
   docker compose logs -f bff
   ```
6. **검증**: `curl -i https://<도메인>/swagger` → 200, 인증서 정상
7. **모바일 전환**: `vibi-mobile` 의 `BFF_BASE_URL` 을 새 도메인으로

> admin-ui(Vite) 는 이미지에 안 들어감(분리 운영엔 불필요). 필요하면 `admin-ui` 에서
> `npm ci && npm run build` 후 산출물을 `src/main/resources/admin/` 에 둔 뒤 재빌드.
