#!/usr/bin/env bash
# Links the Dungeon AI tools into a Dungeon checkout and the agents' skill folders, so that a
# `git pull` in this repository updates them in place. Run it again after new files appear.
#
#   ./install.sh ~/repos/Dungeon
#   ./install.sh ~/repos/Dungeon --skills dungeon-play --skill-dirs ~/.claude/skills,~/.agents/skills
#   ./install.sh ~/repos/Dungeon --overlay ~/repos/my-dungeon-tools
#
# dungeon/ mirrors the checkout: every file is linked at the same relative path and added to the
# checkout's .git/info/exclude. Every folder in skills/ is linked into each skill folder; a
# *-linux folder replaces the skill without the suffix here.
# An overlay is a private folder with the same layout. Its dungeon/ files are linked like the
# shared ones, its files under skills/<shared skill>/ are linked into that skill, and its other
# skill folders are linked as skills of their own.
# Whatever already sits where a link belongs moves to .backup/ in this repository.
set -euo pipefail

usage() {
  echo "usage: install.sh <dungeon checkout> [--skills a,b] [--skill-dirs dir,dir] [--overlay dir]" >&2
  exit 2
}

repo="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
backup="$repo/.backup/$(date +%Y%m%d-%H%M%S)"
(($#)) || usage
dungeon="$(cd "$1" && pwd)"
shift
skills=""
skill_dirs="$HOME/.claude/skills"
overlay=""
while (($#)); do
  (($# >= 2)) || usage
  case "$1" in
    --skills) skills="$2" ;;
    --skill-dirs) skill_dirs="$2" ;;
    --overlay) overlay="$(cd "$2" && pwd)" ;;
    *) usage ;;
  esac
  shift 2
done

# link <path> <target>: makes <path> a symbolic link to <target>.
link() {
  local path="$1" target="$2"
  if [[ -L "$path" ]]; then
    [[ "$(readlink "$path")" != "$target" ]] || return 0
    rm "$path"
  elif [[ -e "$path" ]]; then
    # Kept at the same path below the backup folder.
    mkdir -p "$(dirname "$backup$path")"
    mv "$path" "$backup$path"
  fi
  mkdir -p "$(dirname "$path")"
  ln -s "$target" "$path"
  echo "$path -> $target"
}

# link_skill <name> <folder>: links the skill into every skill folder.
link_skill() {
  local dir
  local -a dirs
  IFS=, read -ra dirs <<<"$skill_dirs"
  for dir in "${dirs[@]}"; do link "${dir/#\~/$HOME}/$1" "$2"; done
}

# dungeon_files <source>: links every file below <source> into the Dungeon checkout and keeps
# it out of git there.
dungeon_files() {
  local source="$1" exclude file rel pattern
  [[ -d "$source" ]] || return 0
  exclude="$(cd "$dungeon" && realpath "$(git rev-parse --git-path info/exclude)")"
  while IFS= read -r -d '' file; do
    rel="${file#"$source"/}"
    link "$dungeon/$rel" "$file"
    git -C "$dungeon" check-ignore -q -- "$rel" && continue
    # Glob characters in the name are escaped; a pattern already listed is not added again.
    pattern="/$(sed 's/[][*?\\]/\\&/g' <<<"$rel")"
    grep -qxF -- "$pattern" "$exclude" 2>/dev/null && continue
    [[ ! -s "$exclude" || -z "$(tail -c1 "$exclude")" ]] || echo >>"$exclude"
    echo "$pattern" >>"$exclude"
  done < <(find "$source" -type f -print0)
}

# The shared skills for this platform as "<name> <folder>" lines.
platform_skills() {
  local dir name
  for dir in "$repo"/skills/*/; do
    dir="${dir%/}"
    name="$(basename "$dir")"
    # dungeon-play has drivers for Windows and Linux only.
    [[ "$(uname -s)" != Darwin || "$name" != dungeon-play* ]] || continue
    if [[ "$name" == *-linux ]]; then
      echo "${name%-linux} $dir"
    elif [[ ! -d "$dir-linux" ]]; then
      echo "$name $dir"
    fi
  done
}

available="$(platform_skills)"
[[ "$(uname -s)" != Darwin ]] || echo "dungeon-play has no macOS driver and is left out" >&2
if [[ -n "$skills" ]]; then
  IFS=, read -ra wanted <<<"$skills"
  for name in "${wanted[@]}"; do
    grep -q "^$name " <<<"$available" || { echo "unknown skill: $name" >&2; exit 2; }
  done
fi

dungeon_files "$repo/dungeon"
while read -r name dir; do
  [[ -z "$skills" || ",$skills," == *",$name,"* ]] || continue
  link_skill "$name" "$dir"
  # The skill calls `dungeon-play`; this link puts its driver on PATH.
  [[ "$name" != dungeon-play ]] || link "$HOME/.local/bin/dungeon-play" "$dir/scripts/play.sh"
done <<<"$available"

if [[ -n "$overlay" ]]; then
  dungeon_files "$overlay/dungeon"
  for dir in "$overlay"/skills/*/; do
    [[ -d "$dir" ]] || continue
    dir="${dir%/}"
    name="$(basename "$dir")"
    # The shared skill's folder on this platform, as platform_skills picks it.
    shared="$repo/skills/$name"
    [[ ! -d "$shared-linux" ]] || shared="$shared-linux"
    if [[ -d "$shared" ]]; then
      while IFS= read -r -d '' file; do
        link "$shared/${file#"$dir"/}" "$file"
      done < <(find "$dir" -type f -print0)
    else
      link_skill "$name" "$dir"
    fi
  done
fi
