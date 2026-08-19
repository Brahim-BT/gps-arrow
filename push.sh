#!/usr/bin/env bash
#
# Commit everything and push, in one command.
#
#     ./push.sh "what changed and why"
#
# This exists because the agent working on this repo cannot push. Its sandbox mounts the tree
# without permission to unlink files, so git cannot clear its own index.lock, and it has no
# credentials — deliberately, since it should not be holding your GitHub token. So pushing stays
# yours, and this makes it one line.
#
# Everything below is intended to be read before it is trusted. The only destructive thing it
# does is remove a stale lock file and two kinds of editor litter, and it names each one first.

set -euo pipefail

# Work on the repo this script lives in, whatever directory you ran it from.
cd "$(dirname "${BASH_SOURCE[0]}")"

# ---------------------------------------------------------------- the message is required
if [ $# -eq 0 ] || [ -z "${1// }" ]; then
    echo "usage: ./push.sh \"what changed and why\"" >&2
    echo >&2
    echo "The message is required. These commits are the only record of this work, so a" >&2
    echo "generic one is worse than stopping and asking you for a real one." >&2
    exit 2
fi
MESSAGE="$1"

# ---------------------------------------------------------------- a stale index.lock
#
# The one destructive step, so it is the most careful. git writes .git/index.lock before
# updating the index and removes it afterwards; the agent's sandbox cannot remove it, so an
# orphan gets left behind and blocks every later write with "File exists".
#
# An orphan is safe to delete. A lock belonging to a RUNNING git process is not — deleting it
# invites two processes to write the index at once. So this only ever removes the lock when no
# git process is running at all, and refuses otherwise.
if [ -e .git/index.lock ]; then
    if pgrep -x git >/dev/null 2>&1; then
        echo "REFUSING to clear .git/index.lock: a git process is running." >&2
        echo "Wait for it to finish, or close whatever is using this repo, then try again." >&2
        exit 1
    fi
    echo "Clearing a stale .git/index.lock (no git process is running):"
    ls -l .git/index.lock | sed 's/^/    /'
    rm -f .git/index.lock
fi

# ---------------------------------------------------------------- known litter
#
# The agent occasionally leaves these behind and cannot delete them itself. Named, not silent,
# so you can see exactly what left the tree.
LITTER="$(find . \( -name '*.bak' -o -name '.probe' \) -not -path './.git/*' 2>/dev/null || true)"
if [ -n "$LITTER" ]; then
    echo "Removing editor litter:"
    echo "$LITTER" | sed 's/^/    /'
    echo "$LITTER" | xargs rm -f
fi

# ---------------------------------------------------------------- stage
git add -A

# Nothing staged is a normal outcome, not a failure — you may have already committed.
if git diff --cached --quiet; then
    echo "Nothing to commit; the working tree matches HEAD."
    exit 0
fi

echo
echo "About to commit:"
git --no-pager diff --cached --stat | sed 's/^/    /'
echo

# ---------------------------------------------------------------- commit and push
git commit -q -m "$MESSAGE"
HASH="$(git rev-parse --short HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

# `set -e` would abort on a failed push without explaining it, and the most likely failure —
# the remote having moved — needs a human decision rather than an automatic merge.
if ! git push origin "$BRANCH"; then
    echo >&2
    echo "PUSH REJECTED. The commit is made locally as $HASH but is not on the remote." >&2
    echo >&2
    echo "The usual cause is that origin/$BRANCH has moved since you last pulled." >&2
    echo "This script will not merge or rebase for you: your tree is running on a phone" >&2
    echo "and a bad merge is expensive. Run 'git pull --rebase' yourself if that is what" >&2
    echo "you want, or ask before resolving it." >&2
    exit 1
fi

echo
echo "Pushed $HASH to origin/$BRANCH."
echo "CI takes about two and a half minutes."
