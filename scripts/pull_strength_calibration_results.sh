#!/usr/bin/env bash
set -euo pipefail

run_id="${1:-v48-m180-a16s2}"
base="${2:-strength-calibration-batch-20260609-plus-toppro}"
remote_project="${REMOTE_PROJECT:-~/hhyresearch/lizzie-strength-calibration-20260609-plus-toppro}"
target_dir="${TARGET_DIR:-target/strength-calibration-results-${run_id}}"
archive_name="strength-calibration-${run_id}-results.tar.gz"
remote_archive="${remote_project}/${base}/${archive_name}"
remote_sha="${remote_archive}.sha256"
gateway_tmp="/tmp/${archive_name}"
gateway_sha="/tmp/${archive_name}.sha256"
local_archive="target/${archive_name}"
local_sha="${local_archive}.sha256"

mkdir -p target

echo "[pull] copying from waffle via lab"
ssh -o BatchMode=yes lab "scp waffle:${remote_archive} ${gateway_tmp} && scp waffle:${remote_sha} ${gateway_sha}"
scp "lab:${gateway_tmp}" "${local_archive}"
scp "lab:${gateway_sha}" "${local_sha}"
ssh -o BatchMode=yes lab "rm -f ${gateway_tmp} ${gateway_sha}"

expected_hash="$(awk '{print $1}' "${local_sha}")"
actual_hash="$(shasum -a 256 "${local_archive}" | awk '{print $1}')"
if [[ "${expected_hash}" != "${actual_hash}" ]]; then
  echo "[error] sha256 mismatch" >&2
  echo "expected ${expected_hash}" >&2
  echo "actual   ${actual_hash}" >&2
  exit 1
fi
echo "[pull] sha256 ok ${actual_hash}"

if [[ -e "${target_dir}" ]]; then
  echo "[error] target exists: ${target_dir}" >&2
  echo "Set TARGET_DIR to a new path or remove the old directory explicitly." >&2
  exit 1
fi
mkdir -p "${target_dir}"
tar -xzf "${local_archive}" -C "${target_dir}"

batch_dir="${target_dir}/${base}"
python3 scripts/verify_strength_calibration_results.py "${batch_dir}" --run-id "${run_id}"
echo "[pull] extracted and verified: ${batch_dir}"
