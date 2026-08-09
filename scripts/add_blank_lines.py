#!/usr/bin/env python3
"""Insert a blank line between every Java statement.

Rules (matching the user's request: "a blank line between each
statement"):

  * After a line ending in ';' (outside any string literal, outside
    any comment), insert one blank line.
  * After a line ending in '}' (or '},') the same way, UNLESS the
    next non-blank, non-comment-only line is a continuation
    (else / catch / finally / while / do / case / default) or just
    punctuation (',', '.', ')', '];', '};', etc.).

The script is safe to run repeatedly: if there's already a blank
line in the right place, it doesn't add a second one.
"""

from __future__ import annotations
import re
import sys
from pathlib import Path


def is_comment_only(stripped: str) -> bool:
    return (stripped.startswith("//")
            or stripped.startswith("*")
            or stripped.startswith("/*"))


def strip_inline_comment(line: str) -> str:
    """Remove a trailing // comment, but respect string literals."""
    in_str = False
    str_ch = ""
    i = 0
    while i < len(line):
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == str_ch:
                in_str = False
        else:
            if c in ("\"", "'"):
                in_str = True
                str_ch = c
            elif c == "/" and i + 1 < len(line) and line[i + 1] == "/":
                return line[:i]
        i += 1
    return line


def ends_statement(code: str) -> bool:
    code = code.rstrip()
    if not code:
        return False
    if code.endswith(";"):
        return True
    # } at end of line is also a statement boundary (closes a block,
    # method, class, or anon-class body). The '},' case is the end of
    # an array element; treat it the same way.
    if code.endswith("}") or code.endswith("},"):
        return True
    return False


CONTINUATION_PREFIXES = (
    "else", "catch", "finally", "while", "do", "case", "default",
)


def is_continuation(line: str) -> bool:
    stripped = line.lstrip()
    for p in CONTINUATION_PREFIXES:
        if stripped == p:
            return True
        if stripped.startswith(p + " "):
            return True
        if stripped.startswith(p + "{"):
            return True
        if stripped.startswith(p + "("):
            return True
    return False


def is_punctuation_only(stripped: str) -> bool:
    """Lines like '},' or '});' that continue a multi-line construct."""
    if not stripped:
        return True
    # Pure punctuation characters that would mean a multi-line array,
    # chained call, etc. is still in progress.
    for ch in (",", ".", ")", "]", "}"):
        if stripped.startswith(ch):
            return True
    if stripped in ("});", "}];", "};", "];"):
        return True
    return False


def process(content: str) -> str:
    lines = content.split("\n")
    out: list[str] = []
    n = len(lines)
    i = 0
    while i < n:
        line = lines[i]
        out.append(line)
        stripped = line.strip()
        if not stripped or is_comment_only(stripped):
            i += 1
            continue
        code = strip_inline_comment(line).rstrip()
        if not ends_statement(code):
            i += 1
            continue
        # Find the next "interesting" line (skip blanks and comment-only
        # lines, which are usually attached to the previous statement
        # and shouldn't get their own gap).
        j = i + 1
        while j < n:
            ns = lines[j].strip()
            if not ns:
                j += 1
                continue
            if is_comment_only(ns):
                j += 1
                continue
            break
        if j >= n:
            i += 1
            continue
        next_line = lines[j]
        ns = next_line.strip()
        if is_continuation(next_line):
            i += 1
            continue
        if is_punctuation_only(ns):
            i += 1
            continue
        # If the LAST emitted line is already blank, don't add another.
        if out and out[-1].strip() == "":
            i += 1
            continue
        out.append("")
        i += 1
    return "\n".join(out)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("Usage: add_blank_lines.py <file> [<file> ...]")
        return 1
    for path_str in argv[1:]:
        p = Path(path_str)
        content = p.read_text(encoding="utf-8")
        new = process(content)
        if new != content:
            p.write_text(new, encoding="utf-8")
            print(f"updated  {p}")
        else:
            print(f"no change {p}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
