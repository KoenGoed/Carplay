#!/usr/bin/env bash
# Wordt door GitHub uitgevoerd: bouwt de app en zet hem klaar als update.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk Autoscherm.apk
NOTES="$(cat WIJZIGINGEN.txt 2>/dev/null || echo 'Nieuwe versie')"
gh release create "v${GITHUB_RUN_NUMBER}" Autoscherm.apk \
  --title "Autoscherm 1.${GITHUB_RUN_NUMBER}" \
  --notes "$NOTES"
