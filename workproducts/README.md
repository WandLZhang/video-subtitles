# workproducts

Local, per-video outputs — **one subfolder per video**. The media/subtitle files here
are **gitignored** (derived from copyrighted video) and mirrored to a private GCS
bucket; only each video's `SOURCE.md` manifest is committed.

```
workproducts/
  <video>/
    SOURCE.md                     source URL · pipeline · language · GCS path   (tracked)
    <video>.zh-HK-yue.srt / .vtt  口語 (ASR) track                              (gitignored)
    <video>.en.srt / .vtt         English track                                (gitignored)
    <video>.mp4 · reports         video + fidelity/QA reports                   (gitignored)
```

To repopulate a video's folder on a new machine, pull from GCS (see each `SOURCE.md`
for the bucket path, or `no-subtitles/docs/ACCESS.md`).
