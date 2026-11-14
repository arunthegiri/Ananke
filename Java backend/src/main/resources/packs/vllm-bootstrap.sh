# Ananke Packs — vLLM bootstrap for an existing Linux GPU machine.
#
# Sent over SSH on stdin and run as `bash -s -- <action> <args…>`. This is the
# ONLY code Ananke runs on a compute target: there is no path for a model or a
# user to inject commands. Every argument is validated by the backend
# (SshRemoteRuntime) and re-validated here. Secrets arrive as base64 variables
# prepended to this script on stdin — never in argv (visible in `ps`) and never
# written to disk, except the vLLM API key, which lives in an owner-only env
# file next to the deployment.
set -euo pipefail
umask 077

ACTION="${1:-}"; shift || true
ROOT="$HOME/.ananke/packs"

die() { echo "ANANKE_ERROR=$1"; echo "ANANKE_ERROR_DETAIL=$2"; exit 1; }
need() { [[ "$2" =~ $3 ]] || die INVALID_INPUT "bad $1"; }

# Sets DIR (not echoed: a die inside $(…) would only exit the subshell and lose the error).
set_dir() { need pack_id "$1" '^[0-9]{1,12}$'; DIR="$ROOT/$1"; }

case "$ACTION" in
probe)
  echo "ANANKE_OS=$(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-unknown}" | tr -cd '[:alnum:] ._-')"
  echo "ANANKE_ARCH=$(uname -m)"
  echo "ANANKE_KERNEL=$(uname -r | tr -cd '[:alnum:]._-')"
  if command -v python3 >/dev/null; then echo "ANANKE_PYTHON=$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])')"; fi
  python3 -c 'import venv' 2>/dev/null && echo "ANANKE_VENV=1" || echo "ANANKE_VENV=0"
  echo "ANANKE_DISK_FREE_GB=$(df -Pk "$HOME" | awk 'NR==2{printf "%d", $4/1048576}')"
  if command -v nvidia-smi >/dev/null; then
    nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits 2>/dev/null \
      | tr -cd '[:alnum:] ,._()\n-' | while IFS= read -r line; do echo "ANANKE_GPU=$line"; done
  else
    echo "ANANKE_NO_NVIDIA_SMI=1"
  fi
  ;;

deploy)
  # deploy <pack_id> <repo> <sha> <port> <served_name> <dtype> <max_len> <gpu_util> <tp> <quant> <tool_parser> <trust_remote_code> <vllm_version>
  [[ $# -eq 13 ]] || die INVALID_INPUT "deploy takes 13 arguments"
  set_dir "$1"; REPO="$2"; SHA="$3"; PORT="$4"; NAME="$5"; DTYPE="$6"; MAXLEN="$7"
  UTIL="$8"; TP="$9"; QUANT="${10}"; PARSER="${11}"; TRC="${12}"; VVER="${13}"
  need repo "$REPO" '^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$'
  need revision "$SHA" '^[0-9a-f]{40}$'
  need port "$PORT" '^[0-9]{4,5}$'
  need served_name "$NAME" '^[a-z0-9-]{1,40}$'
  need dtype "$DTYPE" '^(auto|bfloat16|float16)$'
  need max_len "$MAXLEN" '^(0|[0-9]{3,7})$'
  need gpu_util "$UTIL" '^0\.[0-9]{1,2}$'
  need tp "$TP" '^[1-8]$'
  need quant "$QUANT" '^(none|awq|gptq|fp8|compressed-tensors|bitsandbytes)$'
  need tool_parser "$PARSER" '^(none|llama3_json|hermes|mistral|granite)$'
  need trust "$TRC" '^[01]$'
  need vllm_version "$VVER" '^[0-9]+\.[0-9]+\.[0-9]+$'

  command -v nvidia-smi >/dev/null || die NO_GPU "nvidia-smi not found"
  command -v python3 >/dev/null || die MISSING_PREREQ "python3 is not installed"
  python3 -c 'import venv' 2>/dev/null || die MISSING_PREREQ "python3-venv is not installed"

  mkdir -p "$DIR"
  if [[ -f "$DIR/vllm.pid" ]] && kill -0 "$(cat "$DIR/vllm.pid")" 2>/dev/null; then
    kill -TERM -- "-$(cat "$DIR/vllm.pid")" 2>/dev/null || true; sleep 5
  fi

  # One shared virtualenv per pinned vLLM version.
  VENV="$HOME/.ananke/venvs/vllm-$VVER"
  echo "ANANKE_PHASE=INSTALLING"
  if [[ ! -x "$VENV/bin/vllm" ]]; then
    python3 -m venv "$VENV" || die INSTALL_FAILED "could not create virtualenv"
    "$VENV/bin/pip" install --quiet --upgrade pip >"$DIR/install.log" 2>&1 || die INSTALL_FAILED "pip upgrade failed"
    "$VENV/bin/pip" install --quiet "vllm==$VVER" "huggingface_hub[cli]" >>"$DIR/install.log" 2>&1 \
      || die INSTALL_FAILED "pip install vllm==$VVER failed (see install.log)"
  fi

  echo "ANANKE_PHASE=DOWNLOADING"
  if [[ -n "${ANANKE_HF_TOKEN_B64:-}" ]]; then export HF_TOKEN="$(printf %s "$ANANKE_HF_TOKEN_B64" | base64 -d)"; fi
  "$VENV/bin/python" - "$REPO" "$SHA" "$DIR/model" >"$DIR/download.log" 2>&1 <<'PY' || { unset HF_TOKEN; die DOWNLOAD_FAILED "model download failed (gated repo, token, or disk space — see download.log)"; }
import sys
from huggingface_hub import snapshot_download
snapshot_download(repo_id=sys.argv[1], revision=sys.argv[2], local_dir=sys.argv[3])
PY
  unset HF_TOKEN ANANKE_HF_TOKEN_B64

  printf 'VLLM_API_KEY=%s\n' "$(printf %s "$ANANKE_API_KEY_B64" | base64 -d)" > "$DIR/env"
  chmod 600 "$DIR/env"
  unset ANANKE_API_KEY_B64

  ARGS=(serve "$DIR/model" --host 127.0.0.1 --port "$PORT" --served-model-name "$NAME"
        --dtype "$DTYPE" --gpu-memory-utilization "$UTIL" --tensor-parallel-size "$TP")
  [[ "$MAXLEN" != "0" ]] && ARGS+=(--max-model-len "$MAXLEN")
  [[ "$QUANT" != "none" ]] && ARGS+=(--quantization "$QUANT")
  [[ "$PARSER" != "none" ]] && ARGS+=(--enable-auto-tool-choice --tool-call-parser "$PARSER")
  [[ "$TRC" == "1" ]] && ARGS+=(--trust-remote-code)

  echo "ANANKE_PHASE=STARTING"
  # setsid: vLLM gets its own session/process group, so stop can signal the whole group.
  ( set -a; . "$DIR/env"; set +a; export HF_HUB_OFFLINE=1
    setsid nohup bash -c 'echo $$ > "$0"; exec "$@"' "$DIR/vllm.pid" "$VENV/bin/vllm" "${ARGS[@]}" \
      >"$DIR/vllm.log" 2>&1 </dev/null & )
  sleep 5
  kill -0 "$(cat "$DIR/vllm.pid")" 2>/dev/null || die VLLM_START_FAILED "vLLM exited immediately (see logs)"
  echo "ANANKE_STARTED=1"
  ;;

status)
  set_dir "${1:-}"
  if [[ -f "$DIR/vllm.pid" ]] && kill -0 "$(cat "$DIR/vllm.pid")" 2>/dev/null; then echo "ANANKE_RUNNING=1"; else echo "ANANKE_RUNNING=0"; fi
  ;;

logs)
  set_dir "${1:-}"
  for f in install download vllm; do
    [[ -f "$DIR/$f.log" ]] && { echo "===== $f.log ====="; tail -n 150 "$DIR/$f.log"; }
  done
  true
  ;;

stop)
  set_dir "${1:-}"
  if [[ -f "$DIR/vllm.pid" ]]; then
    PID=$(cat "$DIR/vllm.pid")
    if [[ "$PID" =~ ^[0-9]+$ ]]; then
      kill -TERM -- "-$PID" 2>/dev/null || kill "$PID" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
      kill -KILL -- "-$PID" 2>/dev/null || true
    fi
    rm -f "$DIR/vllm.pid"
  fi
  echo "ANANKE_STOPPED=1"
  ;;

remove)
  set_dir "${1:-}"
  if [[ -f "$DIR/vllm.pid" ]]; then PID=$(cat "$DIR/vllm.pid"); [[ "$PID" =~ ^[0-9]+$ ]] && { kill -TERM -- "-$PID" 2>/dev/null || true; }; fi
  rm -rf -- "$DIR"
  echo "ANANKE_REMOVED=1"
  ;;

*) die INVALID_INPUT "unknown action" ;;
esac
