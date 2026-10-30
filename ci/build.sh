#!/usr/bin/env bash
# Wordt door GitHub uitgevoerd: bouwt de app en zet hem klaar als update.
set -uo pipefail
cd "$(dirname "$0")/.."
if ! gradle assembleDebug --no-daemon 2>&1 | tee build.log; then
  # Fouten als aantekeningen tonen, zodat ze in GitHub (en voor Claude) leesbaar zijn
  grep -E "^e: |error:|What went wrong" -A4 build.log | head -n 60 | while IFS= read -r line; do
    echo "::error::${line//%/%25}"
  done
  exit 1
fi
set -e
cp app/build/outputs/apk/debug/app-debug.apk Autoscherm.apk
NOTES="$(cat WIJZIGINGEN.txt 2>/dev/null || echo 'Nieuwe versie')"
gh release create "v${GITHUB_RUN_NUMBER}" Autoscherm.apk \
  --title "Autoscherm 1.${GITHUB_RUN_NUMBER}" \
  --notes "$NOTES"
