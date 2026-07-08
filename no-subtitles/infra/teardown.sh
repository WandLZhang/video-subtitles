#!/usr/bin/env bash
# Delete the GPU VM (and optionally the networking). The GCS artifact bucket is
# left intact.  PROJECT=my-proj ZONE=asia-southeast1-b bash infra/teardown.sh
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT}"
REGION="${REGION:-asia-southeast1}"
ZONE="${ZONE:-asia-southeast1-b}"
INSTANCE="${INSTANCE:-canto-asr}"
DELETE_NET="${DELETE_NET:-false}"   # set true to also remove subnet + firewall

echo ">> Deleting Workbench instance $INSTANCE..."
gcloud workbench instances delete "$INSTANCE" --location="$ZONE" --project="$PROJECT" --quiet || true

if [ "$DELETE_NET" = true ]; then
  echo ">> Removing networking..."
  gcloud compute firewall-rules delete allow-ssh-iap --project="$PROJECT" --quiet || true
  gcloud compute networks subnets delete "canto-$REGION" --region="$REGION" --project="$PROJECT" --quiet || true
fi
echo ">> Done. GCS artifacts are untouched."
