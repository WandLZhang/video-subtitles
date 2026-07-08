#!/usr/bin/env bash
# Provision an NVIDIA L4 GPU as a Vertex AI Workbench instance in a (possibly fresh)
# GCP project. Draws GPU from the Vertex pool — no Compute Engine GPU quota needed —
# and resets the org policies that block GPU VMs on corp projects.
#
#   PROJECT=my-proj REGION=asia-southeast1 ZONE=asia-southeast1-b bash infra/provision.sh
#
# Pick a ZONE with free L4 supply (US is frequently stocked out).
set -euo pipefail

PROJECT="${PROJECT:?set PROJECT}"
REGION="${REGION:-asia-southeast1}"
ZONE="${ZONE:-asia-southeast1-b}"
INSTANCE="${INSTANCE:-canto-asr}"
MACHINE="${MACHINE:-g2-standard-8}"   # 1x L4. Use g2-standard-96 (+cores=8) for a full node.
CORES="${CORES:-1}"

echo ">> [1/5] Enabling APIs..."
gcloud services enable notebooks.googleapis.com compute.googleapis.com \
  aiplatform.googleapis.com orgpolicy.googleapis.com --project="$PROJECT"

echo ">> [2/5] Resetting blocking org policies to Google defaults..."
_bool(){ printf 'name: projects/%s/policies/%s\nspec:\n  rules:\n  - enforce: false\n' "$PROJECT" "$1" >/tmp/op.yaml; gcloud org-policies set-policy /tmp/op.yaml --quiet >/dev/null; }
_allow(){ printf 'name: projects/%s/policies/%s\nspec:\n  rules:\n  - allowAll: true\n' "$PROJECT" "$1" >/tmp/op.yaml; gcloud org-policies set-policy /tmp/op.yaml --quiet >/dev/null; }
_bool  compute.requireShieldedVm            # so an unsigned NVIDIA kmod can load (Secure Boot off)
_bool  compute.disableGuestAttributesAccess # so gcloud ssh key-exchange works
_allow compute.vmExternalIpAccess           # allow a public IP (simpler egress/SSH)
_allow gcp.resourceLocations                # allow non-US regions where L4 has supply
echo "   waiting ~3 min for org-policy propagation..."; sleep 180

echo ">> [3/5] Network + subnet + IAP-SSH firewall..."
gcloud compute networks describe default --project="$PROJECT" >/dev/null 2>&1 || \
  gcloud compute networks create default --subnet-mode=custom --project="$PROJECT"
gcloud compute networks subnets describe "canto-$REGION" --region="$REGION" --project="$PROJECT" >/dev/null 2>&1 || \
  gcloud compute networks subnets create "canto-$REGION" --network=default --region="$REGION" \
    --range=172.16.0.0/20 --project="$PROJECT"
gcloud compute firewall-rules describe allow-ssh-iap --project="$PROJECT" >/dev/null 2>&1 || \
  gcloud compute firewall-rules create allow-ssh-iap --network=default --direction=INGRESS \
    --action=ALLOW --rules=tcp:22 --source-ranges=35.235.240.0/20 --project="$PROJECT"

echo ">> [4/5] Creating Workbench L4 ($MACHINE) in $ZONE (Secure Boot OFF)..."
gcloud workbench instances create "$INSTANCE" --project="$PROJECT" --location="$ZONE" \
  --machine-type="$MACHINE" --accelerator-type=NVIDIA_L4 --accelerator-core-count="$CORES" \
  --install-gpu-driver --boot-disk-size=200 --boot-disk-type=PD_BALANCED \
  --network="projects/$PROJECT/global/networks/default" \
  --subnet="projects/$PROJECT/regions/$REGION/subnetworks/canto-$REGION" --subnet-region="$REGION" \
  --shielded-secure-boot=false --shielded-vtpm=true --shielded-integrity-monitoring=true
echo "   waiting for ACTIVE..."
until [ "$(gcloud workbench instances describe "$INSTANCE" --location="$ZONE" --project="$PROJECT" --format='value(state)' 2>/dev/null)" = ACTIVE ]; do sleep 20; done

echo ">> [5/5] Installing GPU driver on the host..."
gcloud compute ssh "$INSTANCE" --zone="$ZONE" --project="$PROJECT" --tunnel-through-iap \
  --command="/usr/bin/nvidia-smi >/dev/null 2>&1 || sudo /opt/deeplearning/install-driver.sh; nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv"

echo ">> Ready. SSH in with:"
echo "   gcloud compute ssh $INSTANCE --zone=$ZONE --project=$PROJECT --tunnel-through-iap"
