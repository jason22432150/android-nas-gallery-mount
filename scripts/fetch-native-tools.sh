#!/usr/bin/env bash
# 取得 Android arm64 的 rclone / fusermount3 / libfuse3，放入 jniLibs。
# 優先：從已連線裝置的 Termux 路徑 adb pull（與規格實測一致）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"

TERMUX_BIN="${TERMUX_BIN:-/data/data/com.termux/files/usr/bin}"
TERMUX_LIB="${TERMUX_LIB:-/data/data/com.termux/files/usr/lib}"

pull_via_sdcard() {
  local remote="$1"
  local dest="$2"
  local name
  name="$(basename "$dest")"
  adb shell "su -c 'cp \"$remote\" /sdcard/Download/$name && chmod 644 /sdcard/Download/$name'"
  adb pull "/sdcard/Download/$name" "$dest"
  adb shell "rm /sdcard/Download/$name" >/dev/null 2>&1 || true
  chmod 755 "$dest"
  shasum -a 256 "$dest" | tee "$dest.sha256"
}

if command -v adb >/dev/null && adb get-state 2>/dev/null | grep -q device; then
  echo "==> 從裝置 Termux 拉取工具"
  pull_via_sdcard "$TERMUX_BIN/rclone" "$OUT/librclone.so"
  pull_via_sdcard "$TERMUX_BIN/fusermount3" "$OUT/libfusermount3.so"
  pull_via_sdcard "$TERMUX_LIB/libfuse3.so" "$OUT/libfuse3.so"
  echo "完成。請以 rclone version 在真機 Root 環境驗證。"
  exit 0
fi

if [[ -n "${RCLONE_SRC:-}" && -f "${RCLONE_SRC}" ]]; then
  cp "$RCLONE_SRC" "$OUT/librclone.so"
  chmod 755 "$OUT/librclone.so"
  shasum -a 256 "$OUT/librclone.so" | tee "$OUT/librclone.so.sha256"
fi
if [[ -n "${FUSEMOUNT3_SRC:-}" && -f "${FUSEMOUNT3_SRC}" ]]; then
  cp "$FUSEMOUNT3_SRC" "$OUT/libfusermount3.so"
  chmod 755 "$OUT/libfusermount3.so"
  shasum -a 256 "$OUT/libfusermount3.so" | tee "$OUT/libfusermount3.so.sha256"
fi
if [[ -n "${LIBFUSE3_SRC:-}" && -f "${LIBFUSE3_SRC}" ]]; then
  cp "$LIBFUSE3_SRC" "$OUT/libfuse3.so"
  chmod 755 "$OUT/libfuse3.so"
  shasum -a 256 "$OUT/libfuse3.so" | tee "$OUT/libfuse3.so.sha256"
fi

missing=0
for f in librclone.so libfusermount3.so libfuse3.so; do
  if [[ ! -f "$OUT/$f" ]]; then
    echo "缺少 $OUT/$f"
    missing=1
  fi
done
if [[ "$missing" -ne 0 ]]; then
  cat <<'EOF'
請連接已安裝 Termux rclone／libfuse3 的裝置後重跑：
  ./scripts/fetch-native-tools.sh

或手動指定：
  RCLONE_SRC=... FUSEMOUNT3_SRC=... LIBFUSE3_SRC=... ./scripts/fetch-native-tools.sh

詳見 docs/THIRD_PARTY.md
EOF
  exit 1
fi
