#!/usr/bin/env bash
# 编译 APK 并同时投递：桌面一份 + 服务器一份（二选一上传方式）
#
# 用法：
#   ./tools/publish_apk.sh
#
# 上传方式（环境变量，任选其一）：
#   1) HTTPS 令牌接口（推荐，不用把密码写进脚本）
#      export APK_UPLOAD_URL="https://dx.xckeji.xyz/apk_upload.php"
#      export APK_UPLOAD_TOKEN="wc4_apk_2026"
#   2) FTP（宝塔里开的 FTP 账号）
#      export FTP_HOST="dx.xckeji.xyz" FTP_PORT=21 FTP_USER="xxx" FTP_PASS="xxx"
#      export FTP_DIR="apk"        # 服务器上的目录，不存在会自动创建
#
# 可选：
#   APK_NAME          服务器/桌面上的文件名，默认 TerrainEditor-debug.apk
#   APK_DESKTOP_DIR   桌面目录，默认 ~/Desktop

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

APK_NAME="${APK_NAME:-TerrainEditor-debug.apk}"
APK_DESKTOP_DIR="${APK_DESKTOP_DIR:-$HOME/Desktop}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> 编译 APK"
./gradlew :app:assembleDebug

echo "==> 复制到桌面"
mkdir -p "$APK_DESKTOP_DIR"
cp "$APK_PATH" "$APK_DESKTOP_DIR/$APK_NAME"
cp "$APK_PATH" "$APK_DESKTOP_DIR/Terrain Editor正式版.apk"
echo "    桌面: $APK_DESKTOP_DIR/$APK_NAME"

UPLOADED=0
if [[ -n "${APK_UPLOAD_URL:-}" && -n "${APK_UPLOAD_TOKEN:-}" ]]; then
  echo "==> 上传到服务器（HTTPS 接口）"
  RESP="$(curl -sS --max-time 120 -H "X-Token: ${APK_UPLOAD_TOKEN}" \
      -F "name=${APK_NAME}" -F "apk=@${APK_PATH}" "${APK_UPLOAD_URL}")"
  echo "    $RESP"
  UPLOADED=1
elif [[ -n "${FTP_HOST:-}" && -n "${FTP_USER:-}" && -n "${FTP_PASS:-}" ]]; then
  echo "==> 上传到服务器（FTP）"
  curl -sS --max-time 300 --ftp-create-dirs \
      -T "$APK_PATH" \
      "ftp://${FTP_HOST}:${FTP_PORT:-21}/${FTP_DIR:-apk}/${APK_NAME}" \
      --user "${FTP_USER}:${FTP_PASS}"
  echo "    ftp://${FTP_HOST}/${FTP_DIR:-apk}/${APK_NAME}"
  UPLOADED=1
fi

if [[ "$UPLOADED" == "0" ]]; then
  echo "!! 未配置上传方式，只放到了桌面。"
  echo "   请设置 APK_UPLOAD_URL+APK_UPLOAD_TOKEN，或 FTP_HOST/FTP_USER/FTP_PASS 后重跑。"
fi
