# video-subtitles

Reproducible pipelines to generate subtitle tracks for videos across **modalities**.

| Modality | Folder | Approach |
|----------|--------|----------|
| Video with **no usable subtitles** | [`no-subtitles/`](no-subtitles/) | transcribe the audio (ASR) → colloquial Cantonese (口語) + English |
| YouTube with **existing subtitles** | [`youtube-w-subtitles/`](youtube-w-subtitles/) | bypass player attestation (POT) → intercept signed session URLs → translate & render on-the-fly |
| Web player + an **external 口語 srt** | [`webplayer-w-captions/`](webplayer-w-captions/) | overlay a CantoCaptions/community `.srt` + live English translation on any web player (replaces Substital) |

**Watchlist:** [watchlist.md](watchlist.md) — Cantonese-dub anime catalog (hkanime, 461 titles) + watch picks, as 口語 listening source material.
