#!/usr/bin/env bash
# 等 GitHub Actions 编完 → 自动下载并解出 APK → 放到本机 Download
# 用法：在仓库根目录执行  ./scripts/fetch-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-/sdcard/Download}"
ARTIFACT_NAME="${ARTIFACT_NAME:-infinstall-debug}"
APK_NAME="${APK_NAME:-Infinstall-debug.apk}"
TMP_DIR="$(mktemp -d)"

cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

cd "$ROOT"

if ! command -v gh >/dev/null; then
  echo "需要已安装并登录的 gh（GitHub CLI）"
  exit 1
fi

echo "→ 等待最新一次 Actions 完成…"
# 若当前有正在跑的，就盯着它；否则取最近一次
if gh run list --limit 1 --json databaseId,status,conclusion -q '.[0].status' 2>/dev/null | grep -q in_progress; then
  gh run watch --exit-status
else
  # 没有进行中的也再确认最近一次是否成功
  :
fi

# 再 watch 一次确保失败会退出（若已结束则立刻返回）
RUN_ID="$(gh run list --workflow=android.yml --limit 1 --json databaseId -q '.[0].databaseId')"
if [[ -z "${RUN_ID}" || "${RUN_ID}" == "null" ]]; then
  echo "没有找到 android.yml 的运行记录。请先 push 触发编译。"
  exit 1
fi

STATUS="$(gh run view "$RUN_ID" --json status,conclusion -q '.status + " " + (.conclusion // "")')"
echo "   run #$RUN_ID  $STATUS"

if [[ "$STATUS" != *completed* ]]; then
  gh run watch "$RUN_ID" --exit-status
fi

CONCLUSION="$(gh run view "$RUN_ID" --json conclusion -q '.conclusion')"
if [[ "$CONCLUSION" != "success" ]]; then
  echo "编译失败（$CONCLUSION），打开网页看日志："
  gh run view "$RUN_ID" --web || true
  exit 1
fi

echo "→ 下载产物（gh 会自动解压 artifact，不是留给你手动解 zip）…"
mkdir -p "$TMP_DIR" "$OUT_DIR"
gh run download "$RUN_ID" -n "$ARTIFACT_NAME" -D "$TMP_DIR"

# artifact 里可能是扁平 APK，或带路径
APK_SRC="$(find "$TMP_DIR" -name '*.apk' | head -n 1)"
if [[ -z "$APK_SRC" ]]; then
  echo "下载内容里没有找到 .apk："
  find "$TMP_DIR" -type f
  exit 1
fi

DEST="$OUT_DIR/$APK_NAME"
cp -f "$APK_SRC" "$DEST"
echo "✓ 已就绪：$DEST"
echo "  用系统文件管理器打开 Download，点 $APK_NAME 安装即可。"
