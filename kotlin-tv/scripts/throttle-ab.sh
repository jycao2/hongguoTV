#!/bin/bash
# 匿名 vs 登录 取流对照实验（单臂一进程，避免进程级冷却串味）
# 用法: bash scripts/throttle-ab.sh <videoId> <sessionFile> [probeTimes]
cd "$(dirname "$0")/.." || exit 1
B=./core/build/install/core/bin/core.bat
V=${1:-7486311576044047385}
S=${2:-D:/code/hongguoTV_new/.workbuddy/passport-s2.json}
N=${3:-10}

run() { $B --passport throttle --arm "$1" --video "$V" --session "$S" 2>&1 | iconv -f GBK -t UTF-8 | grep -E '^\[' ; }

echo "=== 阶段1：慢节奏等风控窗口结束（每 120s 一发，最多 $N 次）"
ok=0
for i in $(seq 1 "$N"); do
  out=$(run anon)
  echo "$(date +%H:%M:%S) probe#$i $out"
  case "$out" in
    *成功*) ok=1; break ;;
  esac
  sleep 120
done

if [ "$ok" != "1" ]; then
  echo "=== 未恢复，放弃本轮（仍处窗口内）"
  exit 0
fi

echo "=== 阶段2：A/B 交替三轮（间隔 6s）"
for r in 1 2 3; do
  echo "$(date +%H:%M:%S) R$r 匿名  $(run anon)"
  sleep 6
  echo "$(date +%H:%M:%S) R$r 登录  $(run login)"
  sleep 6
done
echo "=== 完成"
