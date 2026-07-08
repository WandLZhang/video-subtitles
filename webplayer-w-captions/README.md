# webplayer-w-captions

Overlay an **external 口語 SRT** (e.g. from [CantoCaptions](https://github.com/notHulK11/CantoCaptions))
plus a **live English translation** onto *any* web video player (hkanime, etc.) —
100% client-side, no extension. For videos where a Cantonese caption already exists,
so no ASR/OCR is needed.

**Replaces Substital** for this use: it renders its own overlay (no dependency on an
extension detecting the player), *and* it brings a better source sub + a translation +
a timing nudge.

## Use (console / bookmarklet)
1. Open the video page (e.g. hkanime Gintama EP01) and start playback.
2. Paste `bookmarklet.js` into the DevTools console — or save it as a `javascript:` bookmarklet.
3. Give it an **SRT URL** (e.g. a CantoCaptions raw `.srt`). It parses the srt, translates
   each cue (Chrome on-device Translator by default), and renders **口語 (top) + English
   (bottom)** synced to the player.

## Translation quality (read this)
- **On-device Chrome Translator** (`bookmarklet.js`, default): free, no key — but
  *gist-level* and literal. It cannot fix errors in the source: garbage-in → garbage-out
  (e.g. a mis-heard `物探` becomes "geographic exploration").
- **LLM (`bookmarklet-llm.js`, recommended for real use):** a keyed Gemini call that uses
  cross-cue context and **repairs** ASR slips (`過工`→"high blood sugar", `物探`→"spies").
  Same overlay. Set `KEY` in the file (mint one at [AI Studio](https://aistudio.google.com/apikey),
  or in GCP: `gcloud services enable generativelanguage.googleapis.com` + create a key) —
  **never commit the key**. Model defaults to `gemini-flash-lite-latest` (fastest, ~3.5 s /
  50 cues); swap to `gemini-3.5-flash` for higher quality. Batches are parallelized, capped
  with `maxOutputTokens` so the JSON never truncates, and blank-skip on a bad batch.

## Timing
Community srts are often timed to a different release/cut. Nudge live in the console:
`window.SUB_OFFSET = -30` (seconds; +/- until it locks).

## Files
- `overlay.js` — reusable module (parse · timing · translate · render); Node-testable.
- `bookmarklet.js` — paste-and-go, **on-device** translation (no key; prompts for the SRT URL).
- `bookmarklet-llm.js` — paste-and-go, **keyed Gemini** translation (better; set `KEY` first).
- `test.html` + `sample.srt` — local harness. `overlay.test.js` covers the pure logic (`node overlay.test.js`).

## Caveats
- Needs an HTML5 `<video>` on the page (jwplayer/HLS players qualify).
- On-device Translator needs a recent Chrome (~138+) in a secure context (https/localhost).
