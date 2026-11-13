#!/usr/bin/env bash
# 透過 ADB broadcast 操作 App（對應 UI 按鈕），無需截圖模擬點擊。
# 用法：./scripts/adb-cmd.sh <command> [args...]
set -euo pipefail

PKG="com.nas.gallerymount"
RECEIVER="${PKG}/.debug.CommandReceiver"
DATA_DIR="/data/data/${PKG}/files/debug"

# 讀取 App 私有檔（debuggable 用 run-as；否則 root）
read_app_file() {
  local rel="$1"
  adb shell "run-as ${PKG} cat files/debug/${rel} 2>/dev/null" 2>/dev/null \
    || adb shell "su -c 'cat ${DATA_DIR}/${rel}'" 2>/dev/null \
    || true
}

broadcast() {
  local action="$1"
  shift || true
  # 確保 process 已起（Application 可寫 files/debug）
  adb shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1 || true
  sleep 0.3
  if [[ $# -gt 0 ]]; then
    adb shell am broadcast -a "${action}" -n "${RECEIVER}" "$@" >/dev/null
  else
    adb shell am broadcast -a "${action}" -n "${RECEIVER}" >/dev/null
  fi
  sleep 0.6
  echo "--- last_result ---"
  read_app_file last_result.txt
}

cmd="${1:-help}"
if [[ $# -gt 0 ]]; then
  shift
fi

case "${cmd}" in
  recheck) broadcast com.nas.gallerymount.cmd.RECHECK ;;
  save) broadcast com.nas.gallerymount.cmd.SAVE ;;
  test) broadcast com.nas.gallerymount.cmd.TEST ;;
  list) broadcast com.nas.gallerymount.cmd.LIST ;;
  connect)
    adb shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1 || true
    adb shell am broadcast -a com.nas.gallerymount.cmd.CONNECT -n "${RECEIVER}" >/dev/null
    echo "CONNECT 已送出，輪詢 status（Ctrl+C 可停）…"
    for _ in $(seq 1 120); do
      sleep 2
      adb shell am broadcast -a com.nas.gallerymount.cmd.DUMP_STATUS -n "${RECEIVER}" >/dev/null
      sleep 0.4
      status="$(read_app_file status.txt)"
      echo "${status}" | tail -6
      if echo "${status}" | grep -qE 'phase=(Mounted|Failed|Idle)'; then
        break
      fi
    done
    broadcast com.nas.gallerymount.cmd.DUMP_STATUS
    echo "--- status ---"
    read_app_file status.txt
    ;;
  disconnect) broadcast com.nas.gallerymount.cmd.DISCONNECT ;;
  refresh) broadcast com.nas.gallerymount.cmd.REFRESH ;;
  cancel) broadcast com.nas.gallerymount.cmd.CANCEL_SCAN ;;
  status)
    broadcast com.nas.gallerymount.cmd.DUMP_STATUS
    echo "--- status ---"
    read_app_file status.txt
    ;;
  logs)
    broadcast com.nas.gallerymount.cmd.DUMP_LOGS
    echo "--- logs (tail) ---"
    adb shell "run-as ${PKG} tail -n 80 files/debug/app.log 2>/dev/null" 2>/dev/null \
      || adb shell "su -c 'tail -n 80 ${DATA_DIR}/app.log'" 2>/dev/null \
      || true
    ;;
  clear-logs) broadcast com.nas.gallerymount.cmd.CLEAR_LOGS ;;
  show-logs) broadcast com.nas.gallerymount.cmd.SET_SHOW_LOGS --ez show true ;;
  hide-logs) broadcast com.nas.gallerymount.cmd.SET_SHOW_LOGS --ez show false ;;
  set-mount)
    path="${1:?path required}"
    broadcast com.nas.gallerymount.cmd.SET_MOUNT --es path "${path}"
    ;;
  set-smb)
    # 例：./scripts/adb-cmd.sh set-smb --es host 1.2.3.4 --es share raid --es subpath data/x
    broadcast com.nas.gallerymount.cmd.SET_SMB "$@"
    ;;
  help|*)
    cat <<'EOF'
用法：./scripts/adb-cmd.sh <command>

命令（對應 App 按鈕／功能）：
  recheck      重新檢查 Root／工具／裝置
  save         儲存設定
  test         測試連線
  list         列出來源
  connect      連線並掃描（並輪詢 status）
  disconnect   斷線
  refresh      重新整理狀態
  cancel       中斷掃描
  status       dump 狀態文字
  logs         dump 並顯示最近 logs
  clear-logs   清除 logs
  show-logs    UI 顯示 logs
  hide-logs    UI 隱藏 logs
  set-mount <path>
  set-smb [--es host ...] [--es share ...] [--es subpath ...] [--es user ...] ...

讀取結果：
  adb shell run-as com.nas.gallerymount cat files/debug/last_result.txt
  adb shell run-as com.nas.gallerymount cat files/debug/status.txt
  adb logcat -s NasGallery
EOF
    ;;
esac
