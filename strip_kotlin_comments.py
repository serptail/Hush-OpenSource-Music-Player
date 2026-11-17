#!/usr/bin/env python3
"""
Remove every comment from Kotlin sources (.kt / .kts) without touching code.

How it stays safe
-----------------
* A real Kotlin lexer (not regexes): handles //, /* */ (NESTED, as Kotlin allows), KDoc,
  regular strings, triple-quoted raw strings (including the four-quote closing quirk),
  string templates ($x, ${ ... } with arbitrarily nested strings/comments/braces),
  char literals and `backtick identifiers`.
* Whitespace inside string literals is never touched (trailing spaces / blank lines in raw strings survive).
* Verification before every write: the code (strings verbatim, whitespace collapsed, comments
  treated as whitespace) of the original and of the result must be identical, and the result must
  re-lex with zero comments. Any file failing a check is skipped and reported, never written.
* Dry run by default. Nothing is modified without --write.
* Preserves encoding (UTF-8, BOM) and line endings (LF or CRLF). Writes atomically.

Formatting applied (whitespace only)
------------------------------------
* Lines that contained only a comment are deleted.
* Trailing whitespace stripped; runs of blank lines collapsed to one.
* No blank line right after an opening { ( [ or right before a closing } ) ].
* No blank lines at start of file; exactly one trailing newline.
* Optional: --ktlint runs `ktlint --format` afterwards for full re-indentation/style rules.

Usage
-----
    python strip_kotlin_comments.py                  # dry run on current dir
    python strip_kotlin_comments.py --diff app core  # show diffs for these paths
    python strip_kotlin_comments.py --write          # apply
    python strip_kotlin_comments.py --write --ktlint
    python strip_kotlin_comments.py --selftest
"""

from __future__ import annotations

import argparse
import difflib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SKIP_DIRS = {".git", ".gradle", ".idea", ".kotlin", "build", "out", "node_modules", ".svn", ".hg"}


class LexError(Exception):
    pass


def lex(text: str) -> list[tuple[str, str, bool]]:
    """Split text into (kind, text, nested) pieces.

    kind: 'code' | 'str' | 'comment'. 'nested' is True when the piece sits inside a string literal
    (string text itself, or a ${...} template expression within it).
    """
    n = len(text)
    i = 0
    pieces: list[list] = []
    stack: list = []  # 'S' regular string, 'R' raw string, [depth] template expression

    def emit(kind: str, s: str) -> None:
        nested = True if kind == "str" else bool(stack)
        if kind != "comment" and pieces and pieces[-1][0] == kind and pieces[-1][2] == nested:
            pieces[-1][1].append(s)
        else:
            pieces.append([kind, [s], nested])

    while i < n:
        top = stack[-1] if stack else None
        c = text[i]

        if top is None or isinstance(top, list):
            if text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                emit("comment", text[i:j])
                i = j
                continue
            if text.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if text.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif text.startswith("*/", j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
                if depth:
                    raise LexError(f"unterminated block comment at offset {i}")
                emit("comment", text[i:j])
                i = j
                continue
            if text.startswith('"""', i):
                stack.append("R")
                emit("str", '"""')
                i += 3
                continue
            if c == '"':
                stack.append("S")
                emit("str", '"')
                i += 1
                continue
            if c == "'":
                if i + 1 < n and text[i + 1] == "\\":
                    j = text.find("'", i + 3)
                    if j < 0 or "\n" in text[i:j]:
                        raise LexError(f"bad char literal at offset {i}")
                    emit("str", text[i : j + 1])
                    i = j + 1
                    continue
                if i + 2 < n and text[i + 2] == "'" and text[i + 1] != "\n":
                    emit("str", text[i : i + 3])
                    i += 3
                    continue
                emit("code", c)
                i += 1
                continue
            if c == "`":
                j = text.find("`", i + 1)
                if j < 0 or "\n" in text[i:j]:
                    raise LexError(f"unterminated backtick identifier at offset {i}")
                emit("str", text[i : j + 1])
                i = j + 1
                continue
            if top is not None:
                if c == "{":
                    top[0] += 1
                elif c == "}":
                    if top[0] == 0:
                        stack.pop()
                        emit("code", "}")
                        i += 1
                        continue
                    top[0] -= 1
            emit("code", c)
            i += 1
            continue

        if top == "S":
            if c == "\\":
                emit("str", text[i : i + 2])
                i += 2
            elif c == '"':
                stack.pop()
                emit("str", '"')
                i += 1
            elif c == "$" and text.startswith("${", i):
                emit("str", "${")
                stack.append([0])
                i += 2
            elif c == "\n":
                raise LexError(f"newline inside string at offset {i}")
            else:
                emit("str", c)
                i += 1
            continue

        # raw string
        if text.startswith('"""', i):
            j = i
            while j < n and text[j] == '"':
                j += 1
            stack.pop()
            emit("str", text[i:j])
            i = j
        elif c == "$" and text.startswith("${", i):
            emit("str", "${")
            stack.append([0])
            i += 2
        else:
            emit("str", c)
            i += 1

    if stack:
        raise LexError("unterminated string or template at end of file")
    return [(k, "".join(p), nested) for k, p, nested in pieces]


def strip_comments(text: str) -> tuple[str, int]:
    """Return (formatted text without comments, number of comments removed). LF endings expected."""
    pieces = lex(text)
    out: list[str] = []
    last = "\n"
    line = 0
    protected: set[int] = set()  # lines whose terminating newline lives inside a string
    had_comment: set[int] = set()
    skip_ws = False
    removed = 0

    def put(kind: str, nested: bool, s: str) -> None:
        nonlocal last, line, skip_ws
        if skip_ws:
            if kind == "code":
                s = s.lstrip(" \t")
            else:
                skip_ws = False
        if not s:
            return
        if kind == "code":
            skip_ws = False
        out.append(s)
        last = s[-1]
        for _ in range(s.count("\n")):
            if kind == "str" or nested:
                protected.add(line)
            line += 1

    for idx, (kind, s, nested) in enumerate(pieces):
        if kind != "comment":
            put(kind, nested, s)
            continue
        removed += 1
        had_comment.add(line)
        if s.startswith("//"):
            while out:
                trimmed = out[-1].rstrip(" \t")
                if trimmed:
                    out[-1] = trimmed
                    break
                out.pop()
            last = out[-1][-1] if out else "\n"
        elif s.startswith("/*"):
            nxt = pieces[idx + 1][1][0] if idx + 1 < len(pieces) else "\n"
            prev_ws = last in " \t\n"
            if not prev_ws and nxt not in " \t\n":
                put("code", nested, " ")
            elif prev_ws:
                skip_ws = True

    lines = "".join(out).split("\n")
    kept: list[tuple[str, bool]] = []
    for k, ln in enumerate(lines):
        ends_in_string = k in protected
        starts_in_string = (k - 1) in protected
        prot = ends_in_string or starts_in_string
        s = ln if ends_in_string else ln.rstrip(" \t")
        if s.strip() == "" and not prot and k in had_comment:
            continue
        kept.append((s if not (s.strip() == "" and not prot) else "", prot))

    final: list[tuple[str, bool]] = []
    for s, prot in kept:
        blank = s == "" and not prot
        if blank:
            if not final:
                continue
            ps, pp = final[-1]
            if ps == "" and not pp:
                continue
            if not pp and ps.endswith(("{", "(", "[")):
                continue
        elif not prot and s.lstrip().startswith(("}", ")", "]")):
            while final and final[-1] == ("", False):
                final.pop()
        final.append((s, prot))

    while final and final[-1] == ("", False):
        final.pop()
    result = "\n".join(s for s, _ in final)
    return (result + "\n" if result else ""), removed


def canonical(text: str) -> str:
    """Code with strings verbatim, comments as whitespace, whitespace runs collapsed."""
    parts: list[str] = []
    last_ws = False
    for kind, s, _ in lex(text):
        if kind == "str":
            parts.append(s)
            last_ws = False
            continue
        s = " " if kind == "comment" else re.sub(r"\s+", " ", s)
        if last_ws and s.startswith(" "):
            s = s[1:]
        if s:
            parts.append(s)
            last_ws = s.endswith(" ")
    return "".join(parts).strip()


def process_text(original: str) -> tuple[str, int]:
    shebang = ""
    body = original
    if body.startswith("#!"):
        nl = body.find("\n")
        shebang, body = (body, "") if nl < 0 else (body[: nl + 1], body[nl + 1 :])
    new, removed = strip_comments(body)
    if canonical(body) != canonical(new):
        raise LexError("verification failed: code changed (file left untouched)")
    if any(k == "comment" for k, _, _ in lex(new)):
        raise LexError("verification failed: comments remain (file left untouched)")
    if shebang and not new:
        return shebang, removed
    return shebang + new, removed


def process_file(path: Path) -> tuple[bytes, bytes, int]:
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise LexError(f"not valid UTF-8: {e}")
    bom = "\ufeff" if text.startswith("\ufeff") else ""
    text = text[len(bom) :]
    crlf, lf = text.count("\r\n"), text.count("\n") - text.count("\r\n")
    if crlf and lf:
        raise LexError("mixed line endings; normalise them first")
    eol = "\r\n" if crlf else "\n"
    new, removed = process_text(text.replace("\r\n", "\n"))
    return raw, (bom + new.replace("\n", eol)).encode("utf-8"), removed


def iter_files(paths: list[str], include_kts: bool):
    exts = {".kt"} | ({".kts"} if include_kts else set())
    for p in paths:
        path = Path(p)
        if path.is_file():
            if path.suffix in exts:
                yield path
            continue
        for root, dirs, files in os.walk(path):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for f in sorted(files):
                if Path(f).suffix in exts:
                    yield Path(root) / f


def atomic_write(path: Path, data: bytes) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".strip-", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        shutil.copymode(path, tmp)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def selftest() -> int:
    cases = [
        ('val a = "http://x" // c\n', 'val a = "http://x"\n'),
        ("/* a /* b */ c */\nval x = 1\n", "val x = 1\n"),
        ('val s = "${ /* c */ x }"\n', 'val s = "${ x }"\n'),
        ('val s = """\n  // keep\n  /* keep */  \n\n\n"""\n', 'val s = """\n  // keep\n  /* keep */  \n\n\n"""\n'),
        ("val q = '\"' // c\nval r = '/'\nval t = '\\''\n", "val q = '\"'\nval r = '/'\nval t = '\\''\n"),
        ("fun `a // b`() {}\n", "fun `a // b`() {}\n"),
        ("val v = a/**/b\n", "val v = a b\n"),
        ("fun f() {\n\n    // c\n\n    x()\n    // d\n}\n", "fun f() {\n    x()\n}\n"),
        ('val s = """a"""" // c\n', 'val s = """a""""\n'),
        ('val s = "${ listOf("}", /*c*/ 1) }"\n', 'val s = "${ listOf("}", 1) }"\n'),
        ("/** KDoc */\nclass A // tail\n", "class A\n"),
        ("val x = 1 /* a\n b */ + 2\n", "val x = 1 + 2\n"),
        ("// only\n", ""),
        ("#!/usr/bin/env kotlin\n// c\nprintln(1)\n", "#!/usr/bin/env kotlin\nprintln(1)\n"),
        ('val s = "a$b // not comment"\n', 'val s = "a$b // not comment"\n'),
        ("val s = \"\"\"\n${\n  1 // c\n}\n\"\"\"\n", 'val s = """\n${\n  1\n}\n"""\n'),
    ]
    bad = 0
    for src, want in cases:
        try:
            got, _ = process_text(src)
        except LexError as e:
            got = f"<error {e}>"
        if got != want:
            bad += 1
            print(f"FAIL\n  in:   {src!r}\n  want: {want!r}\n  got:  {got!r}")
    for src in ["/* open", '"abc', "val s = \"\"\"x"]:
        try:
            lex(src)
            bad += 1
            print(f"FAIL: expected LexError for {src!r}")
        except LexError:
            pass
    print("selftest:", "OK" if not bad else f"{bad} failure(s)")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Strip all comments from Kotlin files safely.")
    ap.add_argument("paths", nargs="*", default=["."], help="files or directories (default: .)")
    ap.add_argument("--write", action="store_true", help="apply changes (default is dry run)")
    ap.add_argument("--diff", action="store_true", help="print unified diffs")
    ap.add_argument("--no-kts", action="store_true", help="skip .kts (Gradle scripts)")
    ap.add_argument("--ktlint", action="store_true", help="run `ktlint --format` on changed files after writing")
    ap.add_argument("--selftest", action="store_true", help="run built-in tests and exit")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    changed: list[Path] = []
    failed: list[tuple[Path, str]] = []
    total_removed = scanned = 0

    for path in iter_files(args.paths, include_kts=not args.no_kts):
        scanned += 1
        try:
            old, new, removed = process_file(path)
        except (LexError, OSError) as e:
            failed.append((path, str(e)))
            continue
        if old == new:
            continue
        changed.append(path)
        total_removed += removed
        print(f"{'rewrote' if args.write else 'would change'}: {path}  (-{removed} comments)")
        if args.diff:
            sys.stdout.writelines(
                difflib.unified_diff(
                    old.decode("utf-8").splitlines(True),
                    new.decode("utf-8").splitlines(True),
                    f"a/{path}",
                    f"b/{path}",
                )
            )
        if args.write:
            atomic_write(path, new)

    if args.write and args.ktlint and changed:
        exe = shutil.which("ktlint")
        if exe:
            subprocess.run([exe, "--format", *map(str, changed)], check=False)
        else:
            print("ktlint not found on PATH; skipped formatting pass", file=sys.stderr)

    print(f"\nscanned {scanned} files, {'rewrote' if args.write else 'would change'} {len(changed)}, "
          f"{total_removed} comments {'removed' if args.write else 'found'}")
    for path, why in failed:
        print(f"SKIPPED {path}: {why}", file=sys.stderr)
    if not args.write and changed:
        print("dry run: re-run with --write to apply")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
