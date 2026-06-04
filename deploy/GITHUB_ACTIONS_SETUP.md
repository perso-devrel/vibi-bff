# GitHub Actions → Oracle VM 배포 셋업

`.github/workflows/deploy.yml` 이 main push 시 Oracle Ampere(ARM) VM 에 SSH 로 접속해
`git reset --hard origin/main` → `docker compose up -d --build` 를 돌린다. gcloud / WIF /
Secret Manager 없음 — 빌드는 VM 에서, 런타임 시크릿은 VM 의 `deploy/oracle/bff.env` 가 담당.

> VM 자체 부트스트랩(인스턴스 생성·PAYG·방화벽·git clone·bff.env)은 [`oracle/README.md`](./oracle/README.md).
> 이 문서는 **GitHub Actions 가 그 VM 에 배포하기 위한 SSH 시크릿**만 다룬다.

---

## 1) 배포용 SSH 키 발급 (배포 전용, 사람 키 재사용 금지)

로컬에서:

```bash
ssh-keygen -t ed25519 -f vibi-deploy -C "github-actions-deploy" -N ""
# → vibi-deploy (private), vibi-deploy.pub (public)
```

public 키를 VM 의 배포 유저(`authorized_keys`)에 추가:

```bash
ssh-copy-id -i vibi-deploy.pub ubuntu@<VM_PUBLIC_IP>
# 또는 VM 에서 직접:  echo "<vibi-deploy.pub 내용>" >> ~/.ssh/authorized_keys
```

검증 — 이 키로 비번 없이 들어가지고, repo 디렉터리가 보이는지:

```bash
ssh -i vibi-deploy ubuntu@<VM_PUBLIC_IP> 'cd ~/vibi-bff && git rev-parse --short HEAD'
```

> ⚠️ `vibi-deploy` (private) 는 GitHub Secret 으로만 보관. 로컬 파일은 등록 후 삭제.

---

## 2) GitHub Secrets 설정

repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | 값 | 비고 |
|---|---|---|
| `ORACLE_SSH_HOST` | VM public IP 또는 도메인 | Caddy 도메인과 같아도 되고 IP 직접도 됨 |
| `ORACLE_SSH_USER` | `ubuntu` | Oracle Ubuntu 이미지 기본 유저 |
| `ORACLE_SSH_KEY` | `vibi-deploy` private 키 **전체** | `-----BEGIN ... END-----` 줄 포함, 통째로 붙여넣기 |
| `ORACLE_SSH_PORT` | (선택) SSH 포트 | 미설정 시 `22` |

### Variables (선택)

같은 화면의 **Variables** 탭:

| Variable | 값 예시 | 미설정 시 |
|---|---|---|
| `ORACLE_APP_DIR` | `/home/ubuntu/vibi-bff` | `~/vibi-bff` (= `$HOME/vibi-bff`) |

> 앱 런타임 env (PERSO/Auth/DB/R2 등)는 **GitHub 이 아니라 VM 의 `bff.env`** 에 있다.
> 값 회전은 VM 에서 `bff.env` 수정 후 `docker compose up -d` (또는 다음 배포 때 자동 반영).

---

## 3) 검증

`main` 에 빈 커밋 push 하거나 Actions UI → **Run workflow**:

```bash
git commit --allow-empty -m "ci: trigger deploy"
git push origin main
```

워크플로 로그 마지막에 `docker compose ps` 출력(두 컨테이너 `Up`)이 보이면 성공.
이어서:

```bash
curl -i https://<도메인>/swagger     # 200 + 정상 인증서
```

---

## Troubleshooting

### `ssh: handshake failed` / `Permission denied (publickey)`
- `ORACLE_SSH_KEY` 가 public 키거나 일부만 붙여넣어짐 — **private 키 전체**(BEGIN/END 줄 포함)인지 확인.
- public 키가 VM `~ubuntu/.ssh/authorized_keys` 에 없음 — 1) 단계 재실행.
- `ORACLE_SSH_USER` 오타 (Oracle Ubuntu 는 `ubuntu`, Oracle Linux 는 `opc`).

### `dial tcp <ip>:22: i/o timeout`
- VM 콘솔 Security List 에 22 인바운드 없음, 또는 VM iptables 가 막음. (80/443 만 열고 22 빠뜨린 경우)
- 사무실/CI 고정 IP 가 아니므로 22 는 `0.0.0.0/0` 허용이 필요 — 대신 키 인증만 허용(비번 끄기)으로 방어.

### `cd: ~/vibi-bff: No such file or directory`
- VM 에 repo 가 clone 안 됨 — [`oracle/README.md`](./oracle/README.md) 5번(`git clone`) 미완료.
- 경로가 다르면 `ORACLE_APP_DIR` Variable 로 지정.

### 배포는 됐는데 `/swagger` 502 / 빈 응답
- `bff` 컨테이너 부팅 실패 — VM 에서 `cd deploy/oracle && docker compose logs --tail=100 bff`.
- 대개 `bff.env` 필수값(PERSO/Auth/DB) 누락 → AppConfig `require(...)` fail-fast.

### 다운로드 endpoint 만 500
- `bff.env` 의 `R2_BUCKET` / `R2_ACCOUNT_ID` 오타 또는 `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` 만료·권한 부족.
- R2 비우면 로컬 streaming fallback 이지만, Oracle VM 은 디스크 영속 안 하므로 **R2 권장**.

---

## 보안 메모

- 배포 전용 키 — 사람 개인 키 재사용 금지. 유출 시 `authorized_keys` 에서 한 줄만 지우면 폐기.
- VM `sshd` 는 `PasswordAuthentication no` 권장 (키 인증만).
- `bff.env` 는 절대 commit 금지 (`.gitignore` 에 `deploy/oracle/bff.env`).
- `workflow_dispatch` 수동 트리거도 같은 SSH 경로를 쓴다.
