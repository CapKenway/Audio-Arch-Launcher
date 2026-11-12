#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SVG_FILE="${SCRIPT_DIR}/icon.svg"
RES_DIR="${SCRIPT_DIR}/app/src/main/res"

if [[ ! -f "$SVG_FILE" ]]; then
    echo "Error: $SVG_FILE not found."
    exit 1
fi

echo "=== Converting icon.svg to Android mipmap PNGs ==="

declare -A DENSITIES=(
    ["mipmap-mdpi"]=48
    ["mipmap-hdpi"]=72
    ["mipmap-xhdpi"]=96
    ["mipmap-xxhdpi"]=144
    ["mipmap-xxxhdpi"]=192
)

for dir in "${!DENSITIES[@]}"; do
    size="${DENSITIES[$dir]}"
    target_dir="${RES_DIR}/${dir}"
    mkdir -p "$target_dir"
    out_file="${target_dir}/ic_launcher.png"
    echo "[*] Generating ${dir}/ic_launcher.png (${size}x${size})..."

    if command -v rsvg-convert &>/dev/null; then
        rsvg-convert -w "$size" -h "$size" "$SVG_FILE" -o "$out_file"
    elif command -v convert &>/dev/null; then
        convert -background none -density 300 -resize "${size}x${size}" "$SVG_FILE" "$out_file"
    elif command -v ffmpeg &>/dev/null; then
        ffmpeg -y -i "$SVG_FILE" -vf "scale=${size}:${size}" "$out_file" >/dev/null 2>&1
    else
        echo "Error: Need rsvg-convert, ImageMagick convert, or ffmpeg installed."
        exit 1
    fi
done

echo "[✓] All launcher icons generated successfully!"

