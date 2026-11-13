#!/usr/bin/env sh
# Validates the first line of a git commit message (Conventional Commits).
# Rules mirror CONTRIBUTING.md scope table. No Node/npm required.

set -e

COMMIT_MSG_FILE="${1:?usage: validate-commit-msg.sh <commit-msg-file>}"
HEADER=$(sed -n '1p' "$COMMIT_MSG_FILE")

# Merge commits
case "$HEADER" in
  Merge*) exit 0
esac

TYPES='feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert'
SCOPES='deps|lib|docs|ci|build'
PATTERN="^(${TYPES})(\((${SCOPES})\))?: .+"

if ! printf '%s\n' "$HEADER" | grep -qE "$PATTERN"; then
  echo "commit-msg: invalid header — expected: <type>[([scope])]: <subject>"
  echo "  types:  feat fix docs style refactor perf test build ci chore revert"
  echo "  scopes: deps lib docs ci build (optional)"
  echo "  see CONTRIBUTING.md"
  exit 1
fi

# Count characters, not bytes: a shell like dash counts bytes for ${#var}, which would
# reject a valid multi-byte subject well below 100 characters. Perl is present on the CI
# images and macOS; when it is unavailable, fall back to ${#1}, which counts characters in
# bash but bytes in dash.
header_length() {
  if command -v perl >/dev/null 2>&1; then
    printf '%s' "$1" | perl -CS -e 'local $/; my $s = <STDIN>; print length($s);'
  else
    printf '%s' "${#1}"
  fi
}

HEADER_LENGTH=$(header_length "$HEADER")
if [ "$HEADER_LENGTH" -gt 100 ]; then
  echo "commit-msg: header exceeds 100 characters (${HEADER_LENGTH})"
  exit 1
fi

exit 0
