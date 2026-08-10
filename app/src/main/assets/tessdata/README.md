# Tesseract `eng.traineddata`

This directory must contain `eng.traineddata` (LSTM English model, ~22 MB) for
on-device handwriting recognition to work.

## Why this isn't bundled

The repo deliberately omits the 22 MB binary file (it would dominate the diff
history of the project). ML Kit's text recognition handles printed text fine
without it, so the app works without handwriting support as long as you don't
write a tip on a receipt.

## How to fetch it

```bash
# from project root
bash scripts/fetch_tesseract_eng.sh
```

or manually:

```bash
curl -L -o app/src/main/assets/tessdata/eng.traineddata \
  https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
```

(You can also use `eng.traineddata.best` or `eng.traineddata.fast` for
size/speed/accuracy tradeoffs — see tesseract-ocr/tessdata on GitHub.)

## How the app behaves without it

`HandwritingOcr.isAvailable()` returns false. The visual-signal pipeline still
works (yellow highlighter + pen-circle detection), it just falls back to
whatever the ML Kit Latin recognizer returned for the bbox. For most receipts
that means handwritten numbers are misread or skipped. Add the traineddata
file to fix.
