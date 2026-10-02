#!/usr/bin/env bash
# bettergi-pocket 端到端真机/模拟器测试
# 流程：安装 → 授权（悬浮窗/无障碍/通知）→ 展开悬浮球 → 开启投影并选择原神
#       → 启动原神 → 开启自动扫描 → 轮询 logcat 断言（settle/exported/error）
#
# Usage:
#   e2e_scan.sh all                # 全流程
#   e2e_scan.sh connect            # 单步（失败后按阶段重跑）
#   e2e_scan.sh install|grant|projection|game|scan|wait
#   APK=/path/app.apk DEVICE=127.0.0.1:5555 e2e_scan.sh all
#
# 产物：.workbuddy/artifacts/e2e/run_<ts>.log（全过程日志）

set -u

ADB=${ADB:-/Users/esc/.local/share/mise/installs/android-sdk/22.0/platform-tools/adb}
DEVICE=${DEVICE:-127.0.0.1:5555}
APK=${APK:-/Users/esc/Documents/genshin-scanner/bettergi-pocket-debug-20260902-fix24.apk}

PKG=com.bettergi.pocket
A11Y="$PKG/.input.InputAccessibilityService"
GAME=${GAME:-com.miHoYo.Yuanshen}
ART=/Users/esc/Documents/genshin-scanner/.workbuddy/artifacts/e2e
RUN_ID=$(date +%Y%m%d_%H%M%S)
LOG=$ART/run_$RUN_ID.log
SCAN_TIMEOUT=${SCAN_TIMEOUT:-300}

mkdir -p "$ART"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
adb() { "$ADB" -s "$DEVICE" "$@"; }

# 按可见文本点击（uiautomator dump → 解析 bounds → input tap）
tap_text() {
  local pattern="$1" label="${2:-$1}"
  adb shell uiautomator dump /sdcard/bg_window.xml >/dev/null 2>&1
  adb shell cat /sdcard/bg_window.xml > /tmp/bg_window.xml 2>/dev/null || { log "  dump failed"; return 1; }
  local coords
  coords=$(python3 - "$pattern" <<'PY'
import re, sys
pat = sys.argv[1]
xml = open('/tmp/bg_window.xml', encoding='utf-8').read()
best = None
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    t = re.search(r'text="([^"]*)"', node)
    d = re.search(r'content-desc="([^"]*)"', node)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b:
        continue
    hay = (t.group(1) if t else '') + ' ' + (d.group(1) if d else '')
    if re.search(pat, hay, re.I):
        l, tp, r, bt = map(int, b.groups())
        best = ((l + r) // 2, (tp + bt) // 2)
        break
print(f'{best[0]} {best[1]}' if best else '')
PY
)
  if [ -z "$coords" ]; then log "  ✗ 未找到 [$label]"; return 1; fi
  local x y; x=${coords% *}; y=${coords#* }
  log "  ✓ 点击 [$label] @ ($x,$y)"
  adb shell input tap "$x" "$y"
  return 0
}

stage_connect() {
  log "== connect $DEVICE"
  "$ADB" connect "$DEVICE" | tee -a "$LOG"
  adb shell getprop ro.build.version.release | tee -a "$LOG"
  adb shell getprop ro.product.cpu.abi | tee -a "$LOG"
  adb shell pm list packages | grep -iE "mihoyo|genshin|yuanshen" | tee -a "$LOG"
}

stage_install() {
  log "== install $APK"
  adb install -r -d "$APK" 2>&1 | tee -a "$LOG"
}

stage_grant() {
  log "== grant permissions"
  adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow | tee -a "$LOG"
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>&1 | tee -a "$LOG"
  adb shell settings put secure enabled_accessibility_services "$A11Y" | tee -a "$LOG"
  adb shell settings put secure accessibility_enabled 1 | tee -a "$LOG"
  log "  无障碍服务: $(adb shell settings get secure enabled_accessibility_services)"
  log "  无障碍开关: $(adb shell settings get secure accessibility_enabled)"
  # 启动 app（有 overlay 权限即自动拉起前台服务 + 悬浮球）
  adb shell am start -n "$PKG/.MainActivity" 2>&1 | tee -a "$LOG"
  sleep 3
  adb shell dumpsys activity services "$PKG" | grep -c "$PKG" | tee -a "$LOG"
}

# 取悬浮窗（overlay）frame 中心并点击——UIAutomator 不 dump overlay 窗口，故走 dumpsys
tap_overlay() {
  local label="$1"
  local frame
  frame=$(adb shell dumpsys window windows | grep -A25 "u0 $PKG" | grep -m1 -oE "frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]")
  if [ -z "$frame" ]; then log "  ✗ 未找到悬浮窗 frame"; return 1; fi
  local nums x1 y1 x2 y2 x y
  nums=${frame#frame=[}; nums=${nums//[\[\]]/ }
  set -- $nums
  x1=$1; y1=$2; x2=$3; y2=$4
  x=$(( (x1 + x2) / 2 )); y=$(( (y1 + y2) / 2 ))
  log "  ✓ 点击 [$label] @ ($x,$y) frame=[$x1,$y1][$x2,$y2]"
  adb shell input tap "$x" "$y"
}

# 通过 receiver 广播（receiver 内部 startForegroundService 是同进程允许，绕过 shell UID 限制）
# 前置：确保服务在跑（am start MainActivity → 拉起 TriggerForegroundService）
svc_cmd() {
  if ! adb shell dumpsys activity services "$PKG" | grep -q "service.TriggerForegroundService"; then
    adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 2
  fi
  adb shell am broadcast -a "$1" "${@:2}" -p "$PKG" 2>&1 | tail -1 | tee -a "$LOG"
}

stage_bubble() {
  log "== 展开悬浮球面板（overlay 点击）"
  tap_overlay '悬浮球'
  sleep 2
  adb shell dumpsys window windows | grep -A25 "u0 $PKG" | grep -m1 -oE "frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | tee -a "$LOG"
}

stage_projection() {
  log "== 开启投影（共享屏幕）→ 通知 action → 系统 MediaProjection 弹窗"
  svc_cmd "com.bettergi.pocket.action.DEBUG_SET_SCREEN_SHARE" "--ez" "enabled" "true"
  sleep 2
  log "  am start 直启 CapturePermissionActivity（shell 启动不受后台限制）"
  adb shell am start -n "$PKG/.capture.CapturePermissionActivity" 2>&1 | tee -a "$LOG"
  sleep 4
  log "  点系统授权弹窗确认…"
  tap_text '立即开始|马上开始|Start now|START NOW' '投影确认' || log "  ! 未匹配确认按钮"
  sleep 4
  adb shell dumpsys media_projection 2>&1 | head -8 | tee -a "$LOG"
  svc_cmd "com.bettergi.pocket.action.DEBUG_STATUS"
}

stage_game() {
  log "== 启动原神 $GAME"
  adb shell monkey -p "$GAME" -c android.intent.category.LAUNCHER 1 2>&1 | tail -2 | tee -a "$LOG"
  log "  等待游戏加载（60s）…"
  sleep 60
  adb shell dumpsys window | grep -m1 "mCurrentFocus" | tee -a "$LOG"
}

stage_scan() {
  log "== 开启自动扫描"
  svc_cmd "com.bettergi.pocket.action.DEBUG_SET_SCAN" "--ez" "enabled" "true"
  sleep 5
  svc_cmd "com.bettergi.pocket.action.DEBUG_STATUS"
  log "  扫描日志（最近 30 行 BetterGI.Scan）："
  adb logcat -d -s "BetterGI.Scan" -t 30 | tee -a "$LOG"
}

stage_probe() {
  log "== 切换 A11y Overlay 探针（toggle 验证桥模式）"
  svc_cmd "com.bettergi.pocket.action.DEBUG_SET_PROBE"
  sleep 2
  # 探针为 :a11y 进程 TYPE_ACCESSIBILITY_OVERLAY 不可触摸，uiautomator dump 抓不到
  # 验证手段：dumpsys window 看新窗口
  adb shell dumpsys window windows | grep -iE "TYPE_ACCESSIBILITY_OVERLAY|com.bettergi.pocket" | head -5 | tee -a "$LOG"
  # 再 toggle 一次关闭
  sleep 1
  svc_cmd "com.bettergi.pocket.action.DEBUG_SET_PROBE"
  sleep 2
}

stage_swipe() {
  log "== 滑动测试（adb 触发，验证三段 dispatch 链路）"
  svc_cmd "com.bettergi.pocket.action.DEBUG_SWIPE_TEST" "--ei" "startY" "1150" "--ei" "dist" "876"
  sleep 2
  adb logcat -d -t 60 | grep -iE "debug swipe|BetterGI.Scan" | tail -5 | tee -a "$LOG"
}

stage_wait() {
  log "== 等待扫描完成（超时 ${SCAN_TIMEOUT}s）"
  adb logcat -c
  local deadline=$((SECONDS + SCAN_TIMEOUT))
  local done=0
  while [ $SECONDS -lt $deadline ]; do
    if adb logcat -d -s "BetterGI.Scan" | grep -q "finished"; then done=1; break; fi
    sleep 5
  done
  if [ $done -eq 1 ]; then
    log "✓ 扫描结束"
  else
    log "✗ 超时未结束"
  fi
  adb logcat -d -s "BetterGI.Scan" -t 60 | tee -a "$LOG"
  log "== settle/anchor 关键行："
  adb logcat -d | grep -E "page settle|anchor|total|BetterGI.Ocr" | tail -30 | tee -a "$LOG"
}

stage_report() {
  log "== 汇总"
  adb exec-out run-as "$PKG" ls -l files 2>/dev/null | tee -a "$LOG" || log "  (run-as 不可用，用通知分享 GOOD)"
  adb logcat -d -s "BetterGI.Scan" | grep -cE "progress\[artifact\]" | tee -a "$LOG"
  log "日志: $LOG"
}

case "${1:-all}" in
  connect) stage_connect ;;
  install) stage_install ;;
  grant) stage_grant ;;
  bubble) stage_bubble ;;
  projection) stage_projection ;;
  game) stage_game ;;
  scan) stage_scan ;;
  swipe) stage_swipe ;;
  probe) stage_probe ;;
  wait) stage_wait ;;
  report) stage_report ;;
  all)
    stage_connect
    stage_install
    stage_grant
    stage_bubble
    stage_projection
    stage_swipe
    stage_probe
    stage_game
    stage_scan
    stage_wait
    stage_report
    ;;
  *) echo "unknown stage: $1"; exit 1 ;;
esac
