#!/data/data/com.termux/files/usr/bin/sh
# phone-agent.sh — IP-INDEPENDENT phone command bus over git.
#
# WHY THIS EXISTS: the phone is behind NAT and its tunnel address keeps
# rotating ("it changed"), so every attempt to SSH *to* a fixed IP dies. Git
# does not care what the phone's address is. The phone PULLS commands from a
# branch and PUSHES results back — no inbound reachability, no tunnel, no
# secret to read off the screen. This is the mesh_git_relay idea, reduced to
# one script you run once in Termux.
#
# RUN IT (one line, in Termux):   sh phone-agent.sh
# Leave it running. That's the whole job. Everything else is driven from git.
#
# ------------------------------------------------------------------------------
set -u
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export HOME="${HOME:-/data/data/com.termux/files/home}"

REPO_URL="${PHONE_AGENT_REPO:-https://github.com/Zheke32174/understory-passgen}"
IN_BRANCH="${PHONE_AGENT_IN:-claude/phone-in}"     # I push cmd/<ts>.sh here
OUT_BRANCH="${PHONE_AGENT_OUT:-claude/phone-out}"  # phone pushes out/<ts>.txt here
WORK="$HOME/.phone-agent"
POLL="${PHONE_AGENT_POLL:-20}"                      # seconds between pulls

log(){ printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# --- uid-2000 (Shizuku/rish) detection: needed to delete OTHER apps' files ----
# Termux (u0_a###) cannot unlink Operit's files under scoped storage. rish gives
# uid-2000 (shell), which can. If rish isn't wired up, storage-delete commands
# will say so instead of silently freeing nothing.
RISH=""
for c in "$HOME/rish" "$PREFIX/bin/rish" rish; do
  command -v "$c" >/dev/null 2>&1 && { RISH="$c"; break; }
  [ -x "$c" ] && { RISH="$c"; break; }
done
run_priv(){ # run a command as uid-2000 if available, else plain
  if [ -n "$RISH" ]; then "$RISH" -c "$*"; else sh -c "$*"; fi
}
export -f run_priv 2>/dev/null || true

# --- git auth: reuse whatever this phone already has (Termux gh/git creds) -----
command -v git >/dev/null 2>&1 || pkg install -y git >/dev/null 2>&1
mkdir -p "$WORK"; cd "$WORK" || exit 1
if [ ! -d repo/.git ]; then
  log "cloning command bus…"
  git clone --depth 1 "$REPO_URL" repo >/dev/null 2>&1 || { log "CLONE FAILED — phone needs git read access to $REPO_URL"; exit 1; }
fi
cd repo || exit 1
git config user.email "phone@understory" >/dev/null 2>&1
git config user.name  "phone-agent"     >/dev/null 2>&1

push_out(){ # $1=result file basename  $2=path to content
  git fetch -q origin "$OUT_BRANCH" 2>/dev/null && git checkout -q -B "$OUT_BRANCH" "origin/$OUT_BRANCH" 2>/dev/null \
    || git checkout -q -B "$OUT_BRANCH" 2>/dev/null
  mkdir -p out
  cp "$2" "out/$1"
  git add "out/$1" >/dev/null 2>&1
  git commit -q -m "phone result $1" >/dev/null 2>&1
  # verify the push actually landed (proxy can return 0 while sideband fails)
  n=0
  while [ $n -lt 4 ]; do
    if git push -q origin "$OUT_BRANCH" 2>/dev/null; then
      git fetch -q origin "$OUT_BRANCH" 2>/dev/null
      [ "$(git rev-list --count "origin/$OUT_BRANCH..HEAD" 2>/dev/null)" = "0" ] && { log "result pushed: $1"; return 0; }
    fi
    n=$((n+1)); sleep $((n*2))
  done
  log "PUSH FAILED for $1 (will retry next loop)"
  return 1
}

log "phone-agent up. uid-2000=$( [ -n "$RISH" ] && echo yes || echo NO-rish ). polling $IN_BRANCH every ${POLL}s."
mkdir -p "$WORK/done"

while :; do
  git fetch -q origin "$IN_BRANCH" 2>/dev/null
  # list command files newest-first; run any not yet done
  for f in $(git ls-tree -r --name-only "origin/$IN_BRANCH" 2>/dev/null | grep '^cmd/' | sort); do
    id="$(printf '%s' "$f" | tr '/' '_')"
    [ -e "$WORK/done/$id" ] && continue
    log "running $f"
    git show "origin/$IN_BRANCH:$f" > "$WORK/cmd.sh" 2>/dev/null || continue
    out="$WORK/out.txt"
    {
      echo "=== $f @ $(date -u +%FT%TZ) ==="
      echo "uid=$(id -u) rish=$( [ -n "$RISH" ] && echo yes || echo no)"
      sh "$WORK/cmd.sh" 2>&1
      echo "=== exit=$? ==="
    } > "$out" 2>&1
    push_out "$(basename "$f" .sh)-$(date -u +%s).txt" "$out"
    touch "$WORK/done/$id"
  done
  sleep "$POLL"
done
