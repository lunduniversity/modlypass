#!/bin/bash
# Start the modlypass worker in an interactive Slurm job on a GPU node (MVG, manual use).
# Run on the HPC login node. Ctrl-C stops the worker, Ollama and the job.
#
#   worker/start-interactive.sh [worker options...]
#   e.g. worker/start-interactive.sh --bind 0.0.0.0
#
# Settings file (KEY=value lines, no quotes needed): ~/.config/modlypass/settings.conf
#   ACCOUNTNAME=lu2026-x-yy      Slurm account (see: projinfo), required
#   PARTITION=gpua40             optional
#   TIME=2:00:00                 optional
#   OLLAMA_MODULE=ollama/0.32.14 optional
# Environment variables with the same names override the file.
#
# Deliberately thin: `module load` is a shell function and must run in a login shell on the node;
# all checks and logic live in the Scala worker.
set -euo pipefail

conf="${MODLYPASS_SETTINGS:-$HOME/.config/modlypass/settings.conf}"

# Read KEY from the settings file without executing it; surrounding quotes are tolerated.
setting() {
  [[ -f "$conf" ]] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$conf" | tail -n 1 | sed 's/^["'\'']//; s/["'\'']$//'
}

account="${ACCOUNTNAME:-$(setting ACCOUNTNAME)}"
partition="${PARTITION:-$(setting PARTITION)}"; partition="${partition:-gpua40}"
time="${TIME:-$(setting TIME)}"; time="${time:-2:00:00}"
module="${OLLAMA_MODULE:-$(setting OLLAMA_MODULE)}"; module="${module:-ollama/0.32.14}"
repo="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "$account" ]]; then
  echo "[start] no ACCOUNTNAME; put ACCOUNTNAME=<account> in $conf (see: projinfo)" >&2
  exit 1
fi

# Information only (no decisions here): usage before the run, and how busy the GPU partitions are.
# Choosing a partition automatically is logic and belongs in Scala, not in this script.
command -v projinfo >/dev/null && projinfo || true
sinfo -p "gpua40,gpua100,gpua100i,$partition" || true

echo "[start] account=$account partition=$partition time=$time module=$module repo=$repo"
# One task with 8 cores: --ntasks-per-node=8 would start 8 copies of the worker.
exec srun -A "$account" -p "$partition" --gres=gpu:1 -N 1 -n 1 --cpus-per-task=8 -t "$time" --pty \
  bash -lc 'module load "$1" && cd "$2" && shift 2 && exec scala run worker -- "$@"' \
  worker "$module" "$repo" "$@"
