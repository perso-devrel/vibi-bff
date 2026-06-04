#!/usr/bin/env bash
# Oracle VM "함정 1" 방지 — VM 내부 iptables 에 80/443 허용 규칙을 삽입 + 영구 저장.
# (콘솔의 Security List/NSG 는 별도로 열어야 함 — README 1번 참고. 두 겹 다 필요.)
#
# Ubuntu/Debian 이미지 가정. Oracle Linux 면 아래 firewalld 블록 사용.
# 멱등: 이미 규칙 있으면 중복 삽입 안 함.
set -euo pipefail

if ! command -v iptables >/dev/null; then
  echo "iptables 없음 — Oracle Linux 면 firewalld 경로 사용:" >&2
  echo "  sudo firewall-cmd --permanent --add-service=http --add-service=https && sudo firewall-cmd --reload" >&2
  exit 1
fi

add_rule() {
  local port=$1
  if sudo iptables -C INPUT -m state --state NEW -p tcp --dport "$port" -j ACCEPT 2>/dev/null; then
    echo "  ✓ ${port} 이미 허용됨"
  else
    # Oracle 기본 INPUT 체인 끝에 REJECT 룰이 있으므로 그 "앞"에 삽입.
    sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$port" -j ACCEPT
    echo "  + ${port} 허용 규칙 삽입"
  fi
}

echo "▶ iptables 80/443 허용…"
add_rule 80
add_rule 443

echo "▶ 영구 저장 (reboot 후 유지)…"
if command -v netfilter-persistent >/dev/null; then
  sudo netfilter-persistent save
else
  echo "  netfilter-persistent 미설치 — 설치 후 저장:"
  echo "    sudo apt-get update && sudo apt-get install -y iptables-persistent"
  echo "    sudo netfilter-persistent save"
  exit 1
fi

echo "✅ 방화벽 설정 완료. 외부망에서 'curl -v http://<VM_IP>' 로 검증하세요."
echo "   ⚠️ 'iptables -F' 로 전체 플러시 금지 — Oracle 부팅/메타데이터 룰까지 날아감."
