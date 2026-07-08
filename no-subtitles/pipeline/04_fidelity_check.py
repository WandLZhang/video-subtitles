#!/usr/bin/env python3
"""Meaning-fidelity check: does each 口語 cue convey the SAME MEANING as the film's
burned-in Standard Written Chinese (SWC) subtitle shown at that moment?

Character form differs by design (係/是, 唔/不, 佢/他 are the same words) — only MEANING
is judged. It is also segmentation-aware: our track is cut into short VAD units, so one
SWC sentence may span several of our cues; neighbor cues are given as context so a
fragment isn't wrongly flagged. OCR runs via a Gemini vision model on Vertex.

  GOOGLE_CLOUD_PROJECT=my-proj python pipeline/04_fidelity_check.py \
      --srt output/movie.zh-HK-yue.srt --video output/movie.mp4 --samples 48
"""
import argparse, json, os, subprocess, sys, tempfile
from google import genai
from google.genai import types

MODEL = "gemini-3.1-pro-preview"

def parse_srt(path):
    cues = []
    for blk in open(path, encoding="utf-8").read().split("\n\n"):
        lines = [l for l in blk.splitlines() if l.strip()]
        tc = next((l for l in lines if " --> " in l), None)
        if not tc:
            continue
        a, b = tc.split(" --> ")
        cues.append({"start": a.strip(), "end": b.strip()[:12],
                     "text": "\n".join(lines[lines.index(tc)+1:])})
    return cues

def to_s(ts):
    ts = ts.replace(",", "."); hh, mm, rest = ts.split(":")
    return int(hh)*3600 + int(mm)*60 + float(rest)

SCHEMA = {"type": "OBJECT", "properties": {
    "swc": {"type": "STRING"},
    "verdict": {"type": "STRING", "enum": ["match", "contradiction", "no_swc"]}},
    "required": ["swc", "verdict"]}

PROMPT = (
    "This image is the bottom band of a Hong Kong film frame with burned-in Standard "
    "Written Chinese (書面語) subtitles. Transcribe that subtitle text (traditional; "
    "empty if none).\n\n"
    "Then judge MEANING ONLY — ignore script differences (是/係, 不/唔, 他/佢, 的/嘅 are "
    "the same words). Our audio transcription is split into short units by pauses, so the "
    "SWC sentence may cover THIS line plus its neighbors (given for context). Decide:\n"
    "- match: THIS line's meaning is present in / consistent with the SWC sentence\n"
    "- contradiction: THIS line means something genuinely different (likely a mishearing)\n"
    "- no_swc: no burned-in subtitle visible\n\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--srt", required=True)
    ap.add_argument("--video", required=True)
    ap.add_argument("--project", default=os.environ.get("GOOGLE_CLOUD_PROJECT"))
    ap.add_argument("--samples", type=int, default=48)
    ap.add_argument("--out", default="fidelity_report.json")
    args = ap.parse_args()

    cues = parse_srt(args.srt)
    step = max(1, len(cues) // args.samples)
    idxs = list(range(0, len(cues), step))[:args.samples]
    client = genai.Client(vertexai=True, project=args.project, location="global")

    rows = []
    with tempfile.TemporaryDirectory() as td:
        for n, i in enumerate(idxs):
            c = cues[i]
            mid = (to_s(c["start"]) + to_s(c["end"])) / 2
            png = os.path.join(td, f"{n}.png")
            subprocess.run(["ffmpeg", "-copyts", "-ss", f"{mid:.3f}", "-i", args.video,
                            "-vf", "crop=iw:ih*0.24:0:ih*0.76", "-frames:v", "1", "-y", png],
                           capture_output=True)
            if not os.path.exists(png):
                continue
            prev = cues[i-1]["text"] if i > 0 else ""
            nxt = cues[i+1]["text"] if i+1 < len(cues) else ""
            ctx = f"[prev] {prev}\n[THIS] {c['text']}\n[next] {nxt}"
            img = types.Part.from_bytes(data=open(png, "rb").read(), mime_type="image/png")
            try:
                r = client.models.generate_content(
                    model=MODEL, contents=[img, types.Part(text=PROMPT + ctx)],
                    config=types.GenerateContentConfig(
                        temperature=0.0, response_mime_type="application/json",
                        response_schema=SCHEMA))
                v = json.loads(r.text)
            except Exception as e:  # noqa: BLE001
                print(f"[warn] {c['start']}: {e}", file=sys.stderr); continue
            rows.append({"start": c["start"], "verdict": v["verdict"],
                         "swc": v.get("swc", ""), "yue": c["text"]})
            print(f"[{n+1}/{len(idxs)}] {c['start']} -> {v['verdict']}", file=sys.stderr)

    cats = {}
    for r in rows:
        cats[r["verdict"]] = cats.get(r["verdict"], 0) + 1
    judged = cats.get("match", 0) + cats.get("contradiction", 0)
    summary = {"sampled": len(rows), "verdicts": cats,
               "meaning_match": f'{cats.get("match", 0)}/{judged}',
               "match_pct": round(100*cats.get("match", 0)/max(judged, 1), 1),
               "contradictions": [r["start"] for r in rows if r["verdict"] == "contradiction"]}
    json.dump({"summary": summary, "rows": rows}, open(args.out, "w"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
