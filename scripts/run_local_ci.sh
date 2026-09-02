#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
profile="all"
extra_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing profile}"
      shift 2
      ;;
    --dry-run|--require-clean)
      extra_args+=("$1")
      shift
      ;;
    --summary-dir)
      extra_args+=("$1" "${2:?missing summary directory}")
      shift 2
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

python_bin="${LIZZIE_PYTHON:-}"
if [[ -z "$python_bin" ]]; then
  python_bin="$(command -v python3 || command -v python || true)"
fi
if [[ -z "$python_bin" ]]; then
  echo 'Python 3 was not found. Set LIZZIE_PYTHON or add python3 to PATH.' >&2
  exit 1
fi

if [[ -z "${LIZZIE_MAVEN:-}" ]]; then
  if command -v mvn >/dev/null 2>&1; then
    export LIZZIE_MAVEN="$(command -v mvn)"
  else
    candidate="$(find "$repo_root/.tools" -path '*/apache-maven-*/bin/mvn' -type f 2>/dev/null | sort | tail -n 1)"
    [[ -n "$candidate" ]] && export LIZZIE_MAVEN="$candidate"
  fi
fi

cd "$repo_root"
if [[ ${#extra_args[@]} -gt 0 ]]; then
  exec "$python_bin" scripts/run_local_ci.py --profile "$profile" "${extra_args[@]}"
fi
exec "$python_bin" scripts/run_local_ci.py --profile "$profile"
