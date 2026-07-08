# video-subtitles

Reproducible pipelines to generate subtitle tracks for videos across **modalities**.

| Modality | Folder | Approach |
|----------|--------|----------|
| Video with **no usable subtitles** | [`no-subtitles/`](no-subtitles/) | transcribe the audio (ASR) → colloquial Cantonese (口語) + English |
| YouTube with **existing subtitles** | `youtube-w-subtitles/` *(planned)* | extract / OCR existing subs → standardize → translate |

- Each modality folder is self-contained — start with its `README.md`.
- Per-video outputs live in each modality's own `videos/<video>/` (e.g.
  [`no-subtitles/videos/`](no-subtitles/videos/)) — **gitignored** (derived from
  copyrighted video) and mirrored to a private GCS bucket; only each video's
  `SOURCE.md` manifest is tracked.

First run: [`no-subtitles/README.md`](no-subtitles/README.md).
