# fensau100 — 分手100次 / Break Up 100 (2014)

- **Source:** https://www.youtube.com/watch?v=QWq7Xz-AU2s  (public upload; burned-in SWC subtitles)
- **Pipeline:** `no-subtitles` (audio ASR → 口語, then AI-translate → English)
- **Language:** Cantonese (`yue`), Hong Kong colloquial (口語)
- **GCS mirror:** `gs://wz-qwen-test-canto-subs/fensau100/subtitles/`

## Artifacts (this folder — gitignored)
| file | what | status |
|------|------|--------|
| `fensau100.zh-HK-yue.srt` / `.vtt` | 口語 (Qwen3-ASR + mbroformer + pyannote), 1759 cues | ✅ |
| `fensau100.en.srt` / `.vtt` | English (AI-translate-canto-subs, Gemini) | ⏳ generating |
| `fensau100.mp4` | source video (format 18) — fetch via yt-dlp | not stored (large) |
| fidelity report | sentence-level 口語-vs-SWC meaning check | ⏳ running |

## Notes
- Known ASR slips to fix via reference-correction (homophones/proper nouns), e.g. `税基`→`瑞記` around 00:19:55.
