#!/usr/bin/env bash
# Push subtitle artifacts to the private GCS bucket. NEVER commit them to git.
#
#   BUCKET=gs://my-bucket STEM=fensau100 bash pipeline/03_publish.sh out/fensau100.srt out/fensau100.en.srt
set -euo pipefail
BUCKET="${BUCKET:?gs://your-bucket}"
STEM="${STEM:-movie}"
[ "$#" -ge 1 ] || { echo "usage: BUCKET=gs://.. STEM=.. $0 file1.srt [file2.en.srt ...]"; exit 1; }

gcloud storage buckets describe "$BUCKET" >/dev/null 2>&1 || \
  gcloud storage buckets create "$BUCKET" --location=US --uniform-bucket-level-access

for f in "$@"; do gcloud storage cp "$f" "$BUCKET/$STEM/subtitles/"; done
echo ">> Uploaded. Bucket now:"; gcloud storage ls -r "$BUCKET/$STEM/"
