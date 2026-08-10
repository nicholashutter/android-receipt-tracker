#!/usr/bin/env bash
#
# Fetches the Tesseract `eng.traineddata` file (~22 MB) from the official
# tesseract-ocr/tessdata repo on GitHub and drops it into the location the
# app expects. Required for HandwritingOcr to actually work.
#
# Run from the project root:
#
#   bash scripts/fetch_tesseract_eng.sh
#
# Or for the smaller `fast` model (lower accuracy, ~4 MB):
#
#   bash scripts/fetch_tesseract_eng.sh fast
#
# Or for the higher-accuracy `best` LSTM model (~25 MB):
#
#   bash scripts/fetch_tesseract_eng.sh best

set -euo pipefail

ASSET_DIR="app/src/main/assets/tessdata"
DEST="$ASSET_DIR/eng.traineddata"

VARIANT="${1:-default}"

case "$VARIANT" in
  fast)
    URL="https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata.fast"
    ;;
  best)
    URL="https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata.best"
    ;;
  default)
    URL="https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata"
    ;;
  *)
    echo "usage: $0 [fast|best]" >&2
    exit 1
    ;;
esac

mkdir -p "$ASSET_DIR"

if [ -f "$DEST" ]; then
  echo "Already present: $DEST"
  exit 0
fi

echo "Downloading $VARIANT eng.traineddata from"
echo "  $URL"
echo "to"
echo "  $DEST"
echo

curl -L -o "$DEST" "$URL"

echo
echo "Done. Size: $(du -h "$DEST" | cut -f1)"
echo "Rebuild the app and HandwritingOcr will pick it up."
