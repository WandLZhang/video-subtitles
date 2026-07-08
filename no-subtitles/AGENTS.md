# AGENTS.md — bootstrap & play (for an agentic coder)

Goal: on a fresh local machine, get 分手100次 playing with our **口語** (+ **English**)
subtitle overlay — either a local file or streamed — using the artifacts this repo
produced. The tooling is here; the subtitle/video artifacts are **not** in git (they're
in a private GCS bucket you have access to).

## Prerequisites (install if missing)
- `gcloud` — authenticated with an account granted read on the artifact bucket.
- `yt-dlp` — to fetch the source video (a public YouTube upload).
- `mpv` (desktop) **or** `python3` (headless/remote — use the browser player).

## 1. Get this repo
```bash
git clone https://github.com/WandLZhang/video-subtitles.git && cd video-subtitles/no-subtitles
```

## 2. Pull the subtitle artifacts from GCS
```bash
mkdir -p play && cd play
gcloud storage cp -r gs://wz-qwen-test-canto-subs/fensau100/subtitles/* .
# -> fensau100.zh-HK-yue.srt/.vtt  (口語, ASR)   fensau100.en.srt/.vtt  (English)
```

## 3. Get the video
```bash
yt-dlp -f 18 -o fensau100.mp4 "https://www.youtube.com/watch?v=QWq7Xz-AU2s"
```

## 4. Play — choose by environment
**Desktop (mpv), both tracks at once** — 口語 on the bottom, English on top:
```bash
mpv --sub-file=fensau100.zh-HK-yue.srt \
    --secondary-sub-file=fensau100.en.srt \
    --sub-pos=95 --secondary-sub-pos=5 \
    fensau100.mp4
```
Or stream without downloading (mpv pipes through yt-dlp; force format 18):
```bash
mpv --ytdl-format=18 --sub-file=fensau100.zh-HK-yue.srt \
    "https://www.youtube.com/watch?v=QWq7Xz-AU2s"
```

**Headless / remote workstation (no display or sound card):** serve the browser player
and open it in your *local* browser (forward port 8000). Use the repo's Range-capable
server — stdlib `http.server` can't seek.
```bash
cp ../player/serve.py ../player/player.html .
python serve.py 8000
# open  http://localhost:8000/player.html?stem=fensau100
# -> 口語 (blue, top) + English (below) over the film's burned-in SWC (bottom)
```

## Notes
- `player.html?stem=NAME` expects `NAME.mp4`, `NAME.zh-HK-yue.vtt`, `NAME.en.vtt` beside it.
- To regenerate everything from scratch (new film): see `README.md` runbook
  (`infra/` provision GPU → `pipeline/01` transcribe → `02` translate → `03` publish).
- `docs/ACCESS.md` has the same pull/play commands; `pipeline/04`,`05` check fidelity vs the film's SWC.
