#!/usr/bin/env bash
#
# Play Store 피처 그래픽 생성 스크립트 (1024x500)
# ImageMagick 필요: brew install imagemagick
#
# 사용법: ./scripts/generate_feature_graphic.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/screenshots/feature-graphic"
mkdir -p "$OUTPUT_DIR"

# Check ImageMagick
if ! command -v magick &>/dev/null && ! command -v convert &>/dev/null; then
  echo "Error: ImageMagick not found. Install with: brew install imagemagick"
  exit 1
fi

CONVERT="magick"
command -v magick &>/dev/null || CONVERT="convert"

# 언어별 태그라인
declare -A TAGLINES=(
  [en]="Mirror your phone to Tesla"
  [ko]="테슬라에서 스마트폰을 미러링하세요"
  [zh-CN]="将手机镜像到特斯拉"
  [de]="Spiegeln Sie Ihr Telefon auf Tesla"
  [no]="Speil telefonen din til Tesla"
  [fr]="Mirroring de votre téléphone sur Tesla"
  [nl]="Spiegel je telefoon naar Tesla"
  [ja]="スマホをテスラにミラーリング"
  [es]="Refleja tu teléfono en Tesla"
)

LOCALES=(en ko zh-CN de no fr nl ja es)

for locale in "${LOCALES[@]}"; do
  tagline="${TAGLINES[$locale]}"
  output="$OUTPUT_DIR/feature_graphic_${locale}.png"

  echo "Generating feature graphic for $locale: $tagline"

  # Dark gradient background with app name and tagline
  $CONVERT -size 1024x500 \
    -define gradient:angle=135 \
    gradient:'#1a1a2e-#16213e' \
    -gravity center \
    -fill white \
    -font "Helvetica-Bold" \
    -pointsize 72 \
    -annotate +0-60 "Castla" \
    -fill '#cccccc' \
    -font "Helvetica" \
    -pointsize 32 \
    -annotate +0+40 "$tagline" \
    -fill '#FF5252' \
    -font "Helvetica-Bold" \
    -pointsize 20 \
    -annotate +0+100 "Tesla Screen Mirroring" \
    "$output"

  echo "  -> $output"
done

echo ""
echo "Feature graphics generated in: $OUTPUT_DIR/"
echo "Total: ${#LOCALES[@]} images"
