#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
output="$root/build/cloudbase"
mkdir -p "$stage/admin" "$output"
cp "$root/cloudbase/"{scf_bootstrap,server.js,database.js} "$stage/"
cp "$root/app/src/main/assets/admin/"{index.html,admin.js,admin.css} "$stage/admin/"
(
    cd "$stage"
    zip -q study-tv-cloudbase-function.zip scf_bootstrap server.js database.js admin/{index.html,admin.js,admin.css}
)
mv "$stage/study-tv-cloudbase-function.zip" "$output/"
printf '%s\n' "$output/study-tv-cloudbase-function.zip"
