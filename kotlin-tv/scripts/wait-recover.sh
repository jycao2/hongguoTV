#!/usr/bin/env bash
# 低频等待官方取流恢复：用 openhongguo 官方 CLI 探测（它会自己轮换匿名设备），
# 每 6 分钟一发，出货即停。探测走官方实现而不是我们的探针，避免把我们的身份继续打脏。
set -u
OHG=/d/code/hongguoTV_new/.workbuddy/ohg/openhongguo-cli_v0.4.0_windows_amd64
export OHG_CONFIG_DIR=D:/code/hongguoTV_new/.workbuddy/ohg-conf
SERIES=${1:-7296031515048152100}
ROUNDS=${2:-10}
for i in $(seq 1 "$ROUNDS"); do
  OUT=$("$OHG/ohg.exe" play "$SERIES" --episode 1 --url --json 2>&1 | tr -d '\r')
  echo "$(date +%H:%M:%S) [$i] $(echo "$OUT" | head -3 | tr '\n' ' ')"
  case "$OUT" in
    *"http"*) echo "$(date +%H:%M:%S) RECOVERED"; break ;;
  esac
  [ "$i" -lt "$ROUNDS" ] && sleep 360
done
