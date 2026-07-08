#!/usr/bin/env python3
"""Sentence-level meaning fidelity (the rigorous method):

  1. OCR the burned-in SWC at every cue (Gemini vision on Vertex).
  2. Merge consecutive cues sharing the same SWC line into a real SWC *sentence*,
     collecting all the ASR 口語 utterances spoken during it.
  3. For each SWC sentence, an LLM judges whether those 口語 utterances TOGETHER
     capture its meaning (script differences 是/係 ignored) — no arbitrary cutting;
     the whole sentence and all its utterances are judged as one unit.

The SWC is used only as an internal comparison reference; the report contains
per-sentence verdicts + timestamps, not the SWC text. Run:

  GOOGLE_CLOUD_PROJECT=my-proj python pipeline/05_sentence_fidelity.py \
      --srt output/movie.zh-HK-yue.srt --video output/movie.mp4 [--limit N]
"""
import argparse, json, os, subprocess, sys, tempfile
from google import genai
from google.genai import types

MODEL = "gemini-3.1-pro-preview"
DROP = set("，。！？、,.!? \n　")

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

def key(s):
    return "".join(ch for ch in (s or "") if ch not in DROP)

OCR_SCHEMA = {"type": "OBJECT", "properties": {"swc": {"type": "STRING"}}, "required": ["swc"]}
OCR_PROMPT = ("Transcribe ONLY the burned-in Chinese subtitle text in this bottom-band "
              "image (traditional; empty string if none). JSON: {\"swc\": ...}")

JUDGE_SCHEMA = {"type": "OBJECT", "properties": {
    "verdict": {"type": "STRING", "enum": ["captured", "partial", "missed"]},
    "reason": {"type": "STRING"}}, "required": ["verdict", "reason"]}
JUDGE_PROMPT = (
    "A Hong Kong film's official subtitle sentence (Standard Written Chinese) and the "
    "audio-derived colloquial-Cantonese (口語) utterances spoken during it are given. "
    "Ignore script differences (是/係, 不/唔, 他/佢 are the same words). Judge whether the "
    "口語 utterances TOGETHER convey the same meaning as the SWC sentence:\n"
    "- captured: same meaning conveyed\n- partial: part missing or altered\n"
    "- missed: different/wrong meaning (likely mishearing)\n\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--srt", required=True)
    ap.add_argument("--video", required=True)
    ap.add_argument("--project", default=os.environ.get("GOOGLE_CLOUD_PROJECT"))
    ap.add_argument("--limit", type=int, default=0, help="process only the first N cues")
    ap.add_argument("--out", default="sentence_fidelity.json")
    args = ap.parse_args()

    cues = parse_srt(args.srt)
    if args.limit:
        cues = cues[:args.limit]
    client = genai.Client(vertexai=True, project=args.project, location="global")

    # ---- Phase 1: OCR SWC at every cue ----
    with tempfile.TemporaryDirectory() as td:
        for n, c in enumerate(cues):
            mid = (to_s(c["start"]) + to_s(c["end"])) / 2
            png = os.path.join(td, "f.png")
            subprocess.run(["ffmpeg", "-copyts", "-ss", f"{mid:.3f}", "-i", args.video,
                            "-vf", "crop=iw:ih*0.24:0:ih*0.76", "-frames:v", "1", "-y", png],
                           capture_output=True)
            swc = ""
            if os.path.exists(png):
                try:
                    img = types.Part.from_bytes(data=open(png, "rb").read(), mime_type="image/png")
                    r = client.models.generate_content(
                        model=MODEL, contents=[img, types.Part(text=OCR_PROMPT)],
                        config=types.GenerateContentConfig(
                            temperature=0.0, response_mime_type="application/json",
                            response_schema=OCR_SCHEMA))
                    swc = json.loads(r.text).get("swc", "")
                except Exception as e:  # noqa: BLE001
                    print(f"[ocr warn] {c['start']}: {e}", file=sys.stderr)
            c["swc"] = swc
            if (n+1) % 25 == 0:
                print(f"[ocr] {n+1}/{len(cues)}", file=sys.stderr)

    # ---- Phase 2: merge consecutive same-SWC cues into sentences ----
    groups, cur = [], None
    for c in cues:
        k = key(c["swc"])
        if not k:
            cur = None
            continue
        if cur and cur["k"] == k:
            cur["yue"].append(c["text"]); cur["end"] = c["end"]
        else:
            cur = {"k": k, "swc": c["swc"], "start": c["start"], "end": c["end"], "yue": [c["text"]]}
            groups.append(cur)

    # ---- Phase 3: judge meaning capture per SWC sentence ----
    for g in groups:
        u = "\n".join(f"- {t}" for t in g["yue"])
        content = f"{JUDGE_PROMPT}SWC sentence:\n{g['swc']}\n\n口語 utterances:\n{u}"
        try:
            r = client.models.generate_content(
                model=MODEL, contents=content,
                config=types.GenerateContentConfig(
                    temperature=0.0, response_mime_type="application/json",
                    response_schema=JUDGE_SCHEMA))
            v = json.loads(r.text)
            g["verdict"], g["reason"] = v["verdict"], v["reason"]
        except Exception as e:  # noqa: BLE001
            g["verdict"], g["reason"] = "error", str(e)
        print(f"[judge] {g['start']} -> {g['verdict']}", file=sys.stderr)

    cats = {}
    for g in groups:
        cats[g["verdict"]] = cats.get(g["verdict"], 0) + 1
    judged = sum(v for k, v in cats.items() if k in ("captured", "partial", "missed"))
    summary = {"swc_sentences": len(groups), "verdicts": cats,
               "captured_pct": round(100*cats.get("captured", 0)/max(judged, 1), 1),
               "flagged": [{"start": g["start"], "verdict": g["verdict"]}
                           for g in groups if g["verdict"] in ("partial", "missed")]}
    json.dump({"summary": summary, "groups": groups}, open(args.out, "w"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
