"""Builds a GitHub review payload from a review draft.

Usage: python build_review_payload.py <draft.md> <head-sha> <APPROVE|COMMENT|REQUEST_CHANGES> <out.json>
Post:  gh api -X POST repos/Dungeon-CampusMinden/Dungeon/pulls/<N>/reviews --input <out.json>

Draft format: the review body follows a `**Body:**` line and ends at the first `---`.
Each inline comment starts with a line `**`path:line`**` or `**`path:start-end`**`
(PR-head line numbers, RIGHT side); its text runs until the next such line.
"""

import json
import re
import sys

draft_path, head_sha, event, out_path = sys.argv[1:5]
md = open(draft_path, encoding="utf-8").read()

body = md.split("**Body:**", 1)[1].split("---", 1)[0].strip()
parts = re.split(r"^\*\*`([^`]+)`\*\*\s*$", md.split("---", 1)[1], flags=re.M)

comments = []
for loc, text in zip(parts[1::2], parts[2::2]):
    path, _, lines = loc.rpartition(":")
    if not path or not re.fullmatch(r"\d+(-\d+)?", lines):
        sys.exit(f"Comment location needs path:line or path:start-end, got: {loc}")
    comment = {"path": path, "body": text.strip(), "side": "RIGHT"}
    start, _, end = lines.partition("-")
    if end:
        comment.update(start_line=int(start), start_side="RIGHT", line=int(end))
    else:
        comment["line"] = int(start)
    comments.append(comment)

payload = {"commit_id": head_sha, "event": event, "body": body, "comments": comments}
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=1)

print(body)
for c in comments:
    start = f"{c['start_line']}-" if "start_line" in c else ""
    print(f"{c['path']}:{start}{c['line']}")
