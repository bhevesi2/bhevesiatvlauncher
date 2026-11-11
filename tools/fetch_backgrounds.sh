#!/usr/bin/env bash
# Downloads the bundled background photo sets (Unsplash License) and crops them to 1920x1080.
# Each line: <set> <phase> <images.unsplash.com photo path>
set -euo pipefail
OUT=app/src/main/assets/backgrounds
while read -r set phase id; do
  [ -z "$set" ] && continue
  mkdir -p "$OUT/$set"
  tmp=$(mktemp)
  curl -fsSL --retry 3 "https://images.unsplash.com/$id?fm=jpg&q=88&w=2400&fit=max" -o "$tmp"
  convert "$tmp" -auto-orient -resize 1920x1080^ -gravity center -extent 1920x1080 -strip -quality 84 "$OUT/$set/$phase.jpg"
  rm -f "$tmp"
  echo "$set/$phase.jpg <- $id ($(stat -c %s "$OUT/$set/$phase.jpg") bytes)"
done < tools/backgrounds.txt
