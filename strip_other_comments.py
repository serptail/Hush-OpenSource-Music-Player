#!/usr/bin/env python3
"""
Remove comments from the NON-Kotlin files of a project (companion to strip_kotlin_comments.py).

Handled file types
------------------
  yaml          .yml .yaml                     # comments  (GitHub workflows, dependabot)
  toml          .toml                          # comments  (libs.versions.toml, supabase/config.toml)
  sql           .sql                           -- and /* */ (NESTED, as PostgreSQL allows)
  js            .ts .mts .cts .js .mjs .cjs    // and /* */  (Supabase edge functions)
  xml           .xml .svg                      <!-- -->     (AndroidManifest, res/*, brand kit)
  python        .py                            # comments  (tokenize-based)
  properties    .properties                    # and ! full-line comments (gradle.properties)
  gitignore     .gitignore                     # full-line comments
  editorconfig  .editorconfig                  # and ; full-line comments
  markdown      .md                            <!-- --> and [//]: # (...) comments
Not handled on purpose: .kt/.kts (use strip_kotlin_comments.py), .json (no comments exist),
gradlew / gradlew.bat (generated wrapper scripts), shell/batch scripts (no safe lexer exists).

How it stays safe
-----------------
* A real lexer per language (not regexes) so comment markers inside strings are never touched:
    yaml   quoted scalars (multi-line too), flow collections, and block scalars (| and >), whose
           content - for example the shell in a workflow `run: |` - is data, not YAML comments
    toml   basic/literal strings and their multi-line forms
    sql    '...' with '' escapes, E'...' backslash strings, "identifiers", $tag$ dollar quoting
    js     '...' "..." `...${ nested }...` templates and /regex/ literals (previous-token heuristic)
    xml    CDATA, attribute values, processing instructions, DOCTYPE
    python the stdlib tokenizer
    md     fenced code blocks, inline code spans, indented code (left alone when unsure)
* Verification before every write, any failure skips the file and reports it, never writes:
    1. the code (strings verbatim, comments as whitespace) of original and result is identical
    2. the result re-lexes with zero removable comments
    3. a real parser cross-check when available:
         python -> ast.dump equality     xml  -> element tree equality
         toml   -> tomllib/tomli equality  yaml -> PyYAML equality (pip install pyyaml)
* Directive comments are KEPT by default because removing them changes behaviour:
  `// @ts-expect-error`, `/// <reference>`, `// deno-lint-ignore`, `// eslint-disable`, `/*! license */`,
  `# yaml-language-server: $schema`, `#:schema`, `# type: ignore`, `# noqa`, `# -*- coding -*-`,
  shebangs, `/*+ hints */`, `<!-- markdownlint-disable -->`. Use --strip-directives to remove them too.
* Files with no comments are never rewritten (unlike a global reformat, diffs stay minimal).
* Dry run by default. Nothing is modified without --write.
* Preserves encoding (UTF-8, BOM) and line endings (LF or CRLF). Writes atomically.

Formatting applied (whitespace only, and only around removed comments)
----------------------------------------------------------------------
* Lines that contained only a comment are deleted; trailing whitespace before a trailing comment is dropped.
* Blank runs separated only by deleted comments merge into ONE run of the largest original size
  (so Python keeps its two blank lines between top-level defs). Untouched blank runs are left alone.
* No blank line is left right after an opener or right before a closer (languages where that applies).
* No leading blank lines; exactly one trailing newline in files that changed.

Known limits (reported, never guessed)
---------------------------------------
* YAML: `#` lines inside block scalars (`run: |`) are shell/script text, so they are preserved.
* SQL: comments inside $$ ... $$ bodies are preserved unless --sql-bodies is given, which strips them
  only when the body is LANGUAGE sql / plpgsql (or a bare DO block); other languages stay untouched.
* JS/TS: a `/` after `)` `]` `}` is treated as division. `.jsx`/`.tsx` are not supported.

Usage
-----
    python strip_other_comments.py                      # dry run on current dir
    python strip_other_comments.py --diff supabase .github
    python strip_other_comments.py --write
    python strip_other_comments.py --write --only yaml,toml --sql-bodies
    python strip_other_comments.py --selftest
"""

from __future__ import annotations

import argparse
import ast
import difflib
import fnmatch
import io
import os
import re
import shutil
import sys
import tempfile
import tokenize
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

SKIP_DIRS = {".git", ".gradle", ".idea", ".kotlin", "build", "out", "node_modules", ".svn", ".hg",
             ".temp", ".cxx", ".externalNativeBuild", "captures", "__pycache__", ".venv", "venv"}
DEFAULT_EXCLUDES = ["*.min.js", "*.d.ts"]
SELF = Path(__file__).resolve()

NOTES: set[str] = set()

Piece = tuple[str, str, bool]  # (kind: code|str|comment, text, nested/protected)


class LexError(Exception):
    pass


@dataclass
class Options:
    strip_directives: bool = False
    sql_bodies: bool = False


class Out:
    """Collects pieces, merging neighbours of the same kind."""

    def __init__(self) -> None:
        self.p: list[list] = []

    def add(self, kind: str, s: str, nested: bool = False) -> None:
        if not s:
            return
        if kind == "str":
            nested = True
        if kind != "comment" and self.p and self.p[-1][0] == kind and self.p[-1][2] == nested:
            self.p[-1][1].append(s)
        else:
            self.p.append([kind, [s], nested])

    def done(self) -> list[Piece]:
        return [(k, "".join(parts), nst) for k, parts, nst in self.p]


def _eol(text: str, i: int) -> int:
    j = text.find("\n", i)
    return len(text) if j < 0 else j


# --------------------------------------------------------------------------------------- YAML

_BLOCK_HDR = re.compile(r"[|>][+-]?[1-9]?[+-]?")


def _yaml_quote_end(line: str, i: int, q: str) -> int:
    n = len(line)
    while i < n:
        c = line[i]
        if q == '"' and c == "\\":
            i += 2
            continue
        if c == q:
            if q == "'" and i + 1 < n and line[i + 1] == "'":
                i += 2
                continue
            return i + 1
        i += 1
    return -1


def _yaml_block_threshold(line: str, i: int) -> int:
    """Block scalar content must be indented MORE than the returned column."""
    indent = len(line) - len(line.lstrip(" "))
    col, last_dash, rest = indent, None, line[indent:]
    while True:
        m = re.match(r"-[ \t]+", rest)
        if not m:
            break
        last_dash = col
        col += m.end()
        rest = rest[m.end():]
    if i == col:  # header is the value of a sequence item itself: "- |"
        return last_dash if last_dash is not None else indent - 1
    return col


def lex_yaml(text: str, opts: Options) -> list[Piece]:
    o = Out()
    lines = text.split("\n")
    quote: Optional[str] = None
    thr: Optional[int] = None
    flow = 0
    can = True  # at a position where a scalar may start (so a quote really opens a quoted scalar)
    for li, line in enumerate(lines):
        nl = "\n" if li < len(lines) - 1 else ""
        if thr is not None:
            if line.strip() == "" or len(line) - len(line.lstrip(" ")) > thr:
                o.add("str", line + nl)
                continue
            thr = None
        i, n, consumed_nl = 0, len(line), False
        if quote:
            j = _yaml_quote_end(line, 0, quote)
            if j < 0:
                o.add("str", line + nl)
                continue
            o.add("str", line[:j])
            i, quote, can = j, None, False
        elif flow == 0:
            can = True
        while i < n:
            c = line[i]
            if c in " \t":
                j = i
                while j < n and line[j] in " \t":
                    j += 1
                o.add("code", line[i:j])
                i = j
                continue
            if c == "#" and (i == 0 or line[i - 1] in " \t"):
                o.add("comment", line[i:])
                i = n
                break
            if can and c in "'\"":
                j = _yaml_quote_end(line, i + 1, c)
                if j < 0:
                    o.add("str", line[i:] + nl)
                    quote, consumed_nl, i = c, True, n
                    break
                o.add("str", line[i:j])
                i, can = j, False
                continue
            if can and c in "&*!":
                j = i
                while j < n and line[j] not in " \t":
                    j += 1
                o.add("code", line[i:j])
                i = j
                continue
            if c in "-?" and can and (i + 1 == n or line[i + 1] in " \t"):
                o.add("code", c)
                i += 1
                continue
            if c in "|>" and can and flow == 0:
                m = _BLOCK_HDR.match(line, i)
                if m and re.fullmatch(r"[ \t]*(?:#.*)?", line[m.end():]):
                    thr = _yaml_block_threshold(line, i)
                    o.add("code", m.group())
                    i, can = m.end(), False
                    continue
            if c == ":" and (i + 1 == n or line[i + 1] in " \t" or (flow and line[i + 1] in ",]}")):
                can = True
            elif c == ",":
                can = flow > 0
            elif c in "[{" and can:
                flow += 1
            elif c in "]}" and flow:
                flow -= 1
                can = False
            else:
                can = False
            o.add("code", c)
            i += 1
        if not consumed_nl:
            o.add("code", nl)
    if quote:
        raise LexError("unterminated quoted scalar at end of file")
    return o.done()


def verify_yaml(old: str, new: str) -> None:
    try:
        import yaml  # type: ignore
    except ImportError:
        NOTES.add("PyYAML not installed: YAML files were verified by the lexer check only (pip install pyyaml)")
        return
    try:
        a = list(yaml.safe_load_all(old))
    except Exception:
        NOTES.add("some YAML files use features PyYAML cannot parse; those were verified by the lexer check only")
        return
    try:
        b = list(yaml.safe_load_all(new))
    except Exception as e:
        raise LexError(f"verification failed: result no longer parses as YAML ({e})")
    if a != b:
        raise LexError("verification failed: parsed YAML differs")


# --------------------------------------------------------------------------------------- TOML

def lex_toml(text: str, opts: Options) -> list[Piece]:
    o = Out()
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "#":
            j = _eol(text, i)
            o.add("comment", text[i:j])
            i = j
            continue
        if text.startswith('"""', i) or text.startswith("'''", i):
            q = text[i:i + 3]
            j = i + 3
            while j < n:
                if q == '"""' and text[j] == "\\":
                    j += 2
                    continue
                if text.startswith(q, j):
                    k = j
                    while k < n and text[k] == q[0]:
                        k += 1
                    if k - j > 5:
                        raise LexError(f"too many closing quotes at offset {j}")
                    j = k
                    break
                j += 1
            else:
                raise LexError(f"unterminated multi-line string at offset {i}")
            o.add("str", text[i:j])
            i = j
            continue
        if c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                if c == '"' and text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n":
                    raise LexError(f"newline inside string at offset {i}")
                j += 1
            if j >= n:
                raise LexError(f"unterminated string at offset {i}")
            o.add("str", text[i:j + 1])
            i = j + 1
            continue
        o.add("code", c)
        i += 1
    return o.done()


def verify_toml(old: str, new: str) -> None:
    try:
        import tomllib as toml  # type: ignore
    except ImportError:
        try:
            import tomli as toml  # type: ignore
        except ImportError:
            NOTES.add("tomllib/tomli unavailable (needs Python 3.11+): TOML verified by the lexer check only")
            return
    try:
        a = toml.loads(old)
    except Exception:
        NOTES.add("some TOML files did not parse; those were verified by the lexer check only")
        return
    try:
        b = toml.loads(new)
    except Exception as e:
        raise LexError(f"verification failed: result no longer parses as TOML ({e})")
    if a != b:
        raise LexError("verification failed: parsed TOML differs")


# --------------------------------------------------------------------------------------- SQL

_DOLLAR = re.compile(r"\$([A-Za-z_\u0080-\uffff][\w\u0080-\uffff]*)?\$")
_LANG = re.compile(r"\blanguage\s+'?\"?(\w+)", re.I)


def _sql_body_language(text: str, start: int, end: int) -> Optional[str]:
    """Language of a dollar-quoted body occupying text[start:end], from the surrounding statement."""
    j = text.find(";", end)
    after = text[end: (len(text) if j < 0 else j)]
    m = _LANG.search(after)
    if m:
        return m.group(1).lower()
    k = text.rfind(";", 0, start)
    before = text[(k + 1 if k >= 0 else 0): start]
    ms = list(_LANG.finditer(before))
    if ms:
        return ms[-1].group(1).lower()
    if re.search(r"\bdo\s*$", before, re.I):
        return "plpgsql"
    return None


def lex_sql(text: str, opts: Options, _inner: bool = False) -> list[Piece]:
    o = Out()
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("--", i):
            j = _eol(text, i)
            o.add("comment", text[i:j])
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
            o.add("comment", text[i:j])
            i = j
            continue
        if c == "'":
            esc = i > 0 and text[i - 1] in "eE" and (i < 2 or not re.match(r"[\w$]", text[i - 2]))
            j = i + 1
            while j < n:
                if esc and text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    if text[j + 1:j + 2] == "'":
                        j += 2
                        continue
                    break
                j += 1
            if j >= n:
                raise LexError(f"unterminated string at offset {i}")
            o.add("str", text[i:j + 1])
            i = j + 1
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '"':
                    if text[j + 1:j + 2] == '"':
                        j += 2
                        continue
                    break
                j += 1
            if j >= n:
                raise LexError(f"unterminated quoted identifier at offset {i}")
            o.add("str", text[i:j + 1])
            i = j + 1
            continue
        if c == "$" and (i == 0 or not re.match(r"[\w$]", text[i - 1])):
            m = _DOLLAR.match(text, i)
            if m:
                tag = m.group()
                j = text.find(tag, m.end())
                if j < 0:
                    raise LexError(f"unterminated dollar-quoted string at offset {i}")
                body_start, body_end = m.end(), j
                lang = _sql_body_language(text, i, j + len(tag)) if opts.sql_bodies else None
                o.add("str", tag)
                if lang in ("sql", "plpgsql"):
                    for k, s, nst in lex_sql(text[body_start:body_end], opts, True):
                        o.add(k, s, nst if k == "str" else False)
                else:
                    o.add("str", text[body_start:body_end])
                o.add("str", tag)
                i = j + len(tag)
                continue
        o.add("code", c)
        i += 1
    return o.done()


# --------------------------------------------------------------------------------------- JS / TS

_JS_KW = {"return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do",
          "else", "yield", "await"}
_JS_PUNCT = set("(,=:[!&|?{};+-*%<>~^")
_JS_WORD = re.compile(r"[A-Za-z_$\u0080-\uffff][\w$\u0080-\uffff]*|\d[\w.]*")


def _js_regex_ok(prev: Optional[str]) -> bool:
    if prev is None:
        return True
    if prev in _JS_KW:
        return True
    return len(prev) == 1 and prev in _JS_PUNCT


def lex_js(text: str, opts: Options) -> list[Piece]:
    o = Out()
    i, n = 0, len(text)
    stack: list = []  # "T" template text, [depth] template expression
    prev: Optional[str] = None
    while i < n:
        top = stack[-1] if stack else None
        c = text[i]
        if top == "T":
            if c == "\\":
                o.add("str", text[i:i + 2])
                i += 2
            elif c == "`":
                stack.pop()
                o.add("str", "`")
                i += 1
                prev = "str"
            elif c == "$" and text.startswith("${", i):
                o.add("str", "${")
                stack.append([0])
                i += 2
                prev = None
            else:
                o.add("str", c)
                i += 1
            continue
        nested = bool(stack)
        if text.startswith("//", i):
            j = _eol(text, i)
            o.add("comment", text[i:j])
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                raise LexError(f"unterminated block comment at offset {i}")
            o.add("comment", text[i:j + 2])
            i = j + 2
            continue
        if c in "'\"":
            j = i + 1
            while j < n and text[j] != c:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n":
                    raise LexError(f"newline inside string at offset {i}")
                j += 1
            if j >= n:
                raise LexError(f"unterminated string at offset {i}")
            o.add("str", text[i:j + 1])
            i, prev = j + 1, "str"
            continue
        if c == "`":
            stack.append("T")
            o.add("str", "`")
            i += 1
            continue
        if c == "/" and _js_regex_ok(prev):
            j, in_class = i + 1, False
            while j < n:
                ch = text[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == "\n":
                    raise LexError(f"ambiguous '/' (regex or division?) at offset {i}")
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    break
                j += 1
            if j >= n:
                raise LexError(f"unterminated regex at offset {i}")
            j += 1
            while j < n and text[j].isalpha():
                j += 1
            o.add("str", text[i:j])
            i, prev = j, "str"
            continue
        if top is not None:
            if c == "{":
                top[0] += 1
            elif c == "}":
                if top[0] == 0:
                    stack.pop()
                    o.add("str", "}")
                    i += 1
                    continue
                top[0] -= 1
        m = _JS_WORD.match(text, i)
        if m:
            w = m.group()
            o.add("code", w, nested)
            prev = w if not w[0].isdigit() else "0"
            i = m.end()
            continue
        o.add("code", c, nested)
        if not c.isspace():
            prev = c
        i += 1
    if stack:
        raise LexError("unterminated template literal at end of file")
    return o.done()


# --------------------------------------------------------------------------------------- XML

def lex_xml(text: str, opts: Options) -> list[Piece]:
    o = Out()
    i, n = 0, len(text)
    while i < n:
        if text.startswith("<!--", i):
            j = text.find("-->", i + 4)
            if j < 0:
                raise LexError(f"unterminated comment at offset {i}")
            o.add("comment", text[i:j + 3])
            i = j + 3
        elif text.startswith("<![CDATA[", i):
            j = text.find("]]>", i)
            if j < 0:
                raise LexError(f"unterminated CDATA at offset {i}")
            o.add("str", text[i:j + 3])
            i = j + 3
        elif text.startswith("<?", i):
            j = text.find("?>", i)
            if j < 0:
                raise LexError(f"unterminated processing instruction at offset {i}")
            o.add("str", text[i:j + 2])
            i = j + 2
        elif text[i:i + 9].upper() == "<!DOCTYPE":
            j, depth = i + 9, 0
            while j < n:
                ch = text[j]
                if ch in "\"'":
                    k = text.find(ch, j + 1)
                    if k < 0:
                        raise LexError("unterminated quote in DOCTYPE")
                    j = k
                elif ch == "[":
                    depth += 1
                elif ch == "]":
                    depth -= 1
                elif ch == ">" and depth <= 0:
                    break
                j += 1
            if j >= n:
                raise LexError("unterminated DOCTYPE")
            o.add("str", text[i:j + 1])
            i = j + 1
        elif text[i] == "<":
            start, j = i, i + 1
            while j < n and text[j] != ">":
                if text[j] in "\"'":
                    k = text.find(text[j], j + 1)
                    if k < 0:
                        raise LexError(f"unterminated attribute value at offset {j}")
                    o.add("code", text[start:j])
                    o.add("str", text[j:k + 1])
                    start = j = k + 1
                    continue
                j += 1
            if j >= n:
                raise LexError(f"unterminated tag at offset {i}")
            o.add("code", text[start:j + 1])
            i = j + 1
        else:
            j = text.find("<", i)
            j = n if j < 0 else j
            o.add("code", text[i:j])
            i = j
    return o.done()


def _xml_norm(el: ET.Element):
    t = " ".join((el.text or "").split())
    tail = " ".join((el.tail or "").split())
    return (el.tag, sorted(el.attrib.items()), t, tail, [_xml_norm(c) for c in el])


def verify_xml(old: str, new: str) -> None:
    try:
        a = ET.fromstring(old.encode("utf-8"))
    except ET.ParseError as e:
        raise LexError(f"file is not well-formed XML, cannot verify: {e}")
    try:
        b = ET.fromstring(new.encode("utf-8"))
    except ET.ParseError as e:
        raise LexError(f"verification failed: result is not well-formed XML ({e})")
    if _xml_norm(a) != _xml_norm(b):
        raise LexError("verification failed: element tree differs")


# --------------------------------------------------------------------------------------- line-based formats

def lex_properties(text: str, opts: Options) -> list[Piece]:
    o = Out()
    lines = text.split("\n")
    cont = False
    for li, line in enumerate(lines):
        nl = "\n" if li < len(lines) - 1 else ""
        s = line.lstrip(" \t\f")
        if not cont and s[:1] in ("#", "!") and s:
            o.add("code", line[:len(line) - len(s)])
            o.add("comment", s)
            o.add("code", nl)
            cont = False
            continue
        o.add("code", line + nl)
        m = re.search(r"\\+$", line)
        cont = bool(m) and len(m.group()) % 2 == 1
    return o.done()


def _lex_fullline(text: str, markers: str, allow_indent: bool) -> list[Piece]:
    o = Out()
    lines = text.split("\n")
    for li, line in enumerate(lines):
        nl = "\n" if li < len(lines) - 1 else ""
        s = line.lstrip(" \t") if allow_indent else line
        if s and s[0] in markers:
            o.add("code", line[:len(line) - len(s)])
            o.add("comment", s)
            o.add("code", nl)
        else:
            o.add("code", line + nl)
    return o.done()


def lex_gitignore(text: str, opts: Options) -> list[Piece]:
    return _lex_fullline(text, "#", allow_indent=False)


def lex_editorconfig(text: str, opts: Options) -> list[Piece]:
    return _lex_fullline(text, "#;", allow_indent=True)


# --------------------------------------------------------------------------------------- Markdown

_FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
_REFCOMMENT = re.compile(r"""^ {0,3}\[//\]:\s*#\s*(\(.*\)|".*"|'.*')\s*$""")


def lex_md(text: str, opts: Options) -> list[Piece]:
    o = Out()
    lines = text.split("\n")
    fence: Optional[tuple[str, int]] = None
    in_c = False
    for li, line in enumerate(lines):
        nl = "\n" if li < len(lines) - 1 else ""
        pos = 0
        if in_c:
            j = line.find("-->")
            if j < 0:
                o.add("comment", line + nl)
                continue
            o.add("comment", line[:j + 3])
            in_c, pos = False, j + 3
        elif fence:
            m = _FENCE.match(line)
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= fence[1] and not m.group(2).strip():
                fence = None
                o.add("code", line + nl)
            else:
                o.add("str", line + nl)
            continue
        else:
            m = _FENCE.match(line)
            if m and not (m.group(1)[0] == "`" and "`" in m.group(2)):
                fence = (m.group(1)[0], len(m.group(1)))
                o.add("code", line + nl)
                continue
            if _REFCOMMENT.match(line):
                o.add("comment", line)
                o.add("code", nl)
                continue
            if line.startswith("\t") or len(line) - len(line.lstrip(" ")) >= 4:
                o.add("str", line + nl)  # possibly an indented code block: never touch
                continue
        i, n, consumed = pos, len(line), False
        while i < n:
            c = line[i]
            if c == "\\":
                o.add("code", line[i:i + 2])
                i += 2
                continue
            if c == "`":
                k = i
                while k < n and line[k] == "`":
                    k += 1
                run = line[i:k]
                j = k
                while True:
                    j = line.find(run, j)
                    if j < 0:
                        break
                    e = j + len(run)
                    if (j == 0 or line[j - 1] != "`") and (e >= n or line[e] != "`"):
                        break
                    j = e
                if j < 0:  # span may continue on later lines: leave the rest of this line alone
                    o.add("str", line[i:] + nl)
                    consumed = True
                    break
                o.add("str", line[i:j + len(run)])
                i = j + len(run)
                continue
            if line.startswith("<!--", i):
                j = line.find("-->", i + 4)
                if j < 0:
                    o.add("comment", line[i:] + nl)
                    in_c, consumed = True, True
                    break
                o.add("comment", line[i:j + 3])
                i = j + 3
                continue
            o.add("code", c)
            i += 1
        if not consumed:
            o.add("code", nl)
    if in_c:
        raise LexError("unterminated HTML comment at end of file")
    return o.done()


# --------------------------------------------------------------------------------------- rendering

def render_rows(pieces: list[Piece], token_space: bool) -> tuple[list[tuple[str, bool, bool]], int]:
    out: list[list] = []
    line = 0
    protected: set[int] = set()
    had: set[int] = set()
    skip_ws = False
    removed = 0

    def put(kind: str, nested: bool, s: str) -> None:
        nonlocal line, skip_ws
        if skip_ws:
            if kind == "code":
                s = s.lstrip(" \t")
            else:
                skip_ws = False
        if not s:
            return
        if kind == "code":
            skip_ws = False
        out.append([kind, s])
        for _ in range(s.count("\n")):
            if kind == "str" or nested:
                protected.add(line)
            line += 1

    for idx, (kind, s, nested) in enumerate(pieces):
        if kind != "comment":
            put(kind, nested, s)
            continue
        removed += 1
        had.add(line)
        nxt = pieces[idx + 1][1][:1] if idx + 1 < len(pieces) else "\n"
        if nxt == "\n":
            while out and out[-1][0] == "code":
                stripped = out[-1][1].rstrip(" \t")
                if stripped:
                    out[-1][1] = stripped
                    break
                out.pop()
        elif token_space:
            last = out[-1][1][-1] if out else "\n"
            if last not in " \t\n" and nxt not in " \t\n":
                put("code", nested, " ")
            elif last in " \t\n":
                skip_ws = True

    lines = "".join(t for _, t in out).split("\n")
    rows = []
    for k, ln in enumerate(lines):
        ends = k in protected
        prot = ends or (k - 1) in protected
        touched = k in had
        if touched and not ends and ln.strip() == "":
            prot = False
        rows.append((ln.rstrip(" \t") if (touched and not ends) else ln, prot, touched))
    return rows, removed


def assemble(rows: list[tuple[str, bool, bool]], openers: tuple, closers: tuple) -> str:
    out: list[str] = []
    sizes: list[list[str]] = []
    pending: list[str] = []
    touched = False
    for text, prot, had in rows:
        blank = text.strip() == "" and not prot
        if blank and had:  # a line that held only a comment
            touched = True
            sizes.append(pending)
            pending = []
            continue
        if blank:
            pending.append(text)
            continue
        sizes.append(pending)
        pending = []
        if touched:
            keep = max(len(s) for s in sizes)
            if not out:
                keep = 0
            elif (openers and out[-1].rstrip().endswith(openers)) or \
                    (closers and not prot and text.lstrip().startswith(closers)):
                keep = 0
            out.extend([""] * keep)
        else:
            out.extend(x for s in sizes for x in s)
        out.append(text)
        sizes, touched = [], False
    return ("\n".join(out) + "\n") if out else ""


# --------------------------------------------------------------------------------------- verification

def canonical(pieces: list[Piece], collapse: bool) -> str:
    if collapse:  # token languages: whitespace is not significant
        parts: list[str] = []
        last_ws = False
        for kind, s, _ in pieces:
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
    out: list[str] = []
    line = 0
    protected: set[int] = set()
    for kind, s, nested in pieces:
        if kind == "comment":
            continue
        out.append(s)
        for _ in range(s.count("\n")):
            if kind == "str" or nested:
                protected.add(line)
            line += 1
    res = []
    for k, ln in enumerate("".join(out).split("\n")):
        if k in protected:
            res.append(ln)
        elif ln.strip():
            res.append(ln.rstrip(" \t"))
    return "\n".join(res)


# --------------------------------------------------------------------------------------- languages

@dataclass(frozen=True)
class Lang:
    name: str
    exts: tuple = ()
    names: tuple = ()
    lex: Optional[Callable] = None
    token_space: bool = False
    collapse: bool = False
    openers: tuple = ()
    closers: tuple = ()
    keep: Optional[re.Pattern] = None
    shebang: bool = False
    verify: Optional[Callable] = None
    custom: Optional[Callable] = None


def _kept(pat: Optional[re.Pattern], s: str) -> bool:
    return bool(pat and pat.match(s))


def reclass(pieces: list[Piece], keep: Optional[re.Pattern]) -> list[Piece]:
    if not keep:
        return pieces
    return [("str", s, True) if (k == "comment" and keep.match(s)) else (k, s, n) for k, s, n in pieces]


def process_pieces(lang: Lang, text: str, opts: Options) -> tuple[str, int]:
    keep = None if opts.strip_directives else lang.keep
    pieces = reclass(lang.lex(text, opts), keep)
    if not any(k == "comment" for k, _, _ in pieces):
        return text, 0
    rows, removed = render_rows(pieces, lang.token_space)
    new = assemble(rows, lang.openers, lang.closers)
    new_pieces = reclass(lang.lex(new, opts), keep)
    if any(k == "comment" for k, _, _ in new_pieces):
        raise LexError("verification failed: comments remain (file left untouched)")
    if canonical(pieces, lang.collapse) != canonical(new_pieces, lang.collapse):
        raise LexError("verification failed: code changed (file left untouched)")
    if lang.verify:
        lang.verify(text, new)
    return new, removed


_PY_KEEP = re.compile(
    r"#\s*(?:!|-\*-|vim?:|coding[:=]|type:|noqa|pragma|pylint|flake8|fmt:|isort:|mypy:|pyright:|ruff:|"
    r"nosec|pytype:|yapf:)", re.I)


def process_python(lang: Lang, text: str, opts: Options) -> tuple[str, int]:
    try:
        old_ast = ast.dump(ast.parse(text))
    except SyntaxError as e:
        raise LexError(f"python syntax error, cannot verify: {e}")
    keep = None if opts.strip_directives else _PY_KEEP
    comments: dict[int, int] = {}
    prot: set[int] = set()
    fstack: list[int] = []
    fs, fe = getattr(tokenize, "FSTRING_START", None), getattr(tokenize, "FSTRING_END", None)
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            if tok.type == tokenize.COMMENT:
                if not (keep and keep.match(tok.string)):
                    comments.setdefault(tok.start[0], tok.start[1])
            elif tok.type == tokenize.STRING and tok.end[0] > tok.start[0]:
                prot.update(range(tok.start[0], tok.end[0] + 1))
            elif fs is not None and tok.type == fs:
                fstack.append(tok.start[0])
            elif fe is not None and tok.type == fe and fstack:
                s = fstack.pop()
                if tok.end[0] > s:
                    prot.update(range(s, tok.end[0] + 1))
    except (tokenize.TokenError, IndentationError) as e:
        raise LexError(f"tokenize failed: {e}")
    if not comments:
        return text, 0
    rows = []
    for r, line in enumerate(text.split("\n"), start=1):
        if r in comments:
            rows.append((line[:comments[r]].rstrip(" \t"), r in prot, True))
        else:
            rows.append((line, r in prot, False))
    new = assemble(rows, ("(", "[", "{", ":"), (")", "]", "}"))
    try:
        if ast.dump(ast.parse(new)) != old_ast:
            raise LexError("verification failed: syntax tree changed (file left untouched)")
        left = [t for t in tokenize.generate_tokens(io.StringIO(new).readline)
                if t.type == tokenize.COMMENT and not (keep and keep.match(t.string))]
    except (SyntaxError, tokenize.TokenError, IndentationError) as e:
        raise LexError(f"verification failed: result does not parse ({e})")
    if left:
        raise LexError("verification failed: comments remain (file left untouched)")
    return new, len(comments)


_JS_KEEP = re.compile(
    r"//\s*(?:@ts-|@jsx|@vitest|deno-|eslint-|prettier-ignore|istanbul|c8 |#\s*source|biome-)"
    r"|///\s*<|/\*[!@]|/\*\s*(?:eslint|global |istanbul|webpack)")
_YAML_KEEP = re.compile(r"#\s*(?:yaml-language-server:|yamllint\b|vim?:|@formatter|prettier-ignore|cspell:)")
_TOML_KEEP = re.compile(r"#(?::schema|\s*taplo|\s*prettier-ignore)")
_SQL_KEEP = re.compile(r"/\*\+|--\s*(?:sqlfluff|noqa|pgformatter)")
_MD_KEEP = re.compile(r"<!--\s*(?:/?toc\b|markdownlint|prettier-ignore|omit\b|cspell|vale\b|ALL-CONTRIBUTORS)", re.I)

LANGS: list[Lang] = [
    Lang("yaml", exts=(".yml", ".yaml"), lex=lex_yaml, keep=_YAML_KEEP, verify=verify_yaml),
    Lang("toml", exts=(".toml",), lex=lex_toml, keep=_TOML_KEEP, verify=verify_toml),
    Lang("sql", exts=(".sql",), lex=lex_sql, token_space=True, collapse=True,
         openers=("(", "["), closers=(")", "]"), keep=_SQL_KEEP),
    Lang("js", exts=(".ts", ".mts", ".cts", ".js", ".mjs", ".cjs"), lex=lex_js, token_space=True, collapse=True,
         openers=("{", "(", "["), closers=("}", ")", "]"), keep=_JS_KEEP, shebang=True),
    Lang("xml", exts=(".xml", ".svg"), lex=lex_xml, verify=verify_xml),
    Lang("python", exts=(".py",), custom=process_python),
    Lang("properties", exts=(".properties",), lex=lex_properties),
    Lang("gitignore", names=(".gitignore",), lex=lex_gitignore),
    Lang("editorconfig", names=(".editorconfig",), lex=lex_editorconfig),
    Lang("markdown", exts=(".md",), lex=lex_md, keep=_MD_KEEP),
]
BY_NAME = {l.name: l for l in LANGS}


def process_text(lang: Lang, text: str, opts: Options) -> tuple[str, int]:
    if lang.custom:
        return lang.custom(lang, text, opts)
    shebang, body = "", text
    if lang.shebang and body.startswith("#!"):
        nl = body.find("\n")
        shebang, body = (body, "") if nl < 0 else (body[:nl + 1], body[nl + 1:])
    new, removed = process_pieces(lang, body, opts)
    if not removed:
        return text, 0
    return shebang + new, removed


def process_file(path: Path, lang: Lang, opts: Options) -> tuple[bytes, bytes, int]:
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise LexError(f"not valid UTF-8: {e}")
    bom = "\ufeff" if text.startswith("\ufeff") else ""
    text = text[len(bom):]
    crlf, lf = text.count("\r\n"), text.count("\n") - text.count("\r\n")
    if crlf and lf:
        raise LexError("mixed line endings; normalise them first")
    eol = "\r\n" if crlf else "\n"
    new, removed = process_text(lang, text.replace("\r\n", "\n"), opts)
    if not removed:
        return raw, raw, 0
    return raw, (bom + new.replace("\n", eol)).encode("utf-8"), removed


# --------------------------------------------------------------------------------------- files / CLI

def lang_for(path: Path, enabled: dict[str, Lang]) -> Optional[Lang]:
    for l in enabled.values():
        if path.name in l.names or (l.exts and path.suffix.lower() in l.exts):
            return l
    return None


def iter_files(paths: list[str], enabled: dict[str, Lang], excludes: list[str]):
    def excluded(p: Path) -> bool:
        if p.resolve() == SELF:
            return True
        posix = p.as_posix()
        return any(fnmatch.fnmatch(p.name, g) or fnmatch.fnmatch(posix, g) for g in excludes)

    for p in paths:
        path = Path(p)
        if path.is_file():
            l = lang_for(path, enabled)
            if l and not excluded(path):
                yield path, l
            continue
        for root, dirs, files in os.walk(path):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for f in sorted(files):
                fp = Path(root) / f
                l = lang_for(fp, enabled)
                if l and not excluded(fp):
                    yield fp, l


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
        ("yaml", "a: 1  # c\n# only\nb: 'x # y'\n", "a: 1\nb: 'x # y'\n"),
        ("yaml", "run: |\n  echo hi # keep\n  # keep\nk: v # c\n", "run: |\n  echo hi # keep\n  # keep\nk: v\n"),
        ("yaml", "# yaml-language-server: $schema=x\na: 1\n", "# yaml-language-server: $schema=x\na: 1\n"),
        ("toml", 'a = "x # y" # c\n# only\nb = 1\n', 'a = "x # y"\nb = 1\n'),
        ("toml", 'a = """\n# keep\n"""\n', 'a = """\n# keep\n"""\n'),
        ("sql", "select 1; -- c\n/* a /* b */ c */\nselect '--x';\n", "select 1;\nselect '--x';\n"),
        ("sql", "select $$ -- keep $$;\n", "select $$ -- keep $$;\n"),
        ("js", 'const a = "//x"; // c\n/* b */\nconst b = `${ 1 /* c */ }`;\n',
         'const a = "//x";\nconst b = `${ 1 }`;\n'),
        ("js", "// @ts-expect-error\nx();\n", "// @ts-expect-error\nx();\n"),
        ("js", "const r = /a\\/\\/b/; // c\n", "const r = /a\\/\\/b/;\n"),
        ("xml", '<a b="http://x"><!-- c -->\n<![CDATA[<!-- k -->]]></a>\n',
         '<a b="http://x">\n<![CDATA[<!-- k -->]]></a>\n'),
        ("python", "x = 1  # c\n# only\ny = '# no'\n", "x = 1\ny = '# no'\n"),
        ("python", "# noqa\nx = 1\n", "# noqa\nx = 1\n"),
        ("properties", "# c\na=1\nb=2\\\n# cont\n", "a=1\nb=2\\\n# cont\n"),
        ("gitignore", "# c\n*.log\n", "*.log\n"),
        ("editorconfig", "; c\n# d\n[*]\n", "[*]\n"),
        ("markdown", "a <!-- c --> b\n`<!-- k -->`\n```\n<!-- k -->\n```\n", "a  b\n`<!-- k -->`\n```\n<!-- k -->\n```\n"),
    ]
    opts = Options()
    bad = 0
    for name, src, want in cases:
        try:
            got, _ = process_text(BY_NAME[name], src, opts)
        except LexError as e:
            got = f"<error {e}>"
        if got != want:
            bad += 1
            print(f"FAIL [{name}]\n  in:   {src!r}\n  want: {want!r}\n  got:  {got!r}")
    for name, src in [("js", "/* open"), ("sql", "'abc"), ("xml", "<!-- open"), ("toml", '"abc')]:
        try:
            BY_NAME[name].lex(src, opts)
            bad += 1
            print(f"FAIL: expected LexError for [{name}] {src!r}")
        except LexError:
            pass
    print("selftest:", "OK" if not bad else f"{bad} failure(s)")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Strip comments from non-Kotlin project files safely.")
    ap.add_argument("paths", nargs="*", default=["."], help="files or directories (default: .)")
    ap.add_argument("--write", action="store_true", help="apply changes (default is dry run)")
    ap.add_argument("--diff", action="store_true", help="print unified diffs")
    ap.add_argument("--only", help="comma list of types to process: " + ",".join(BY_NAME))
    ap.add_argument("--skip", help="comma list of types to skip")
    ap.add_argument("--exclude", action="append", default=[], metavar="GLOB", help="skip matching files (repeatable)")
    ap.add_argument("--strip-directives", action="store_true",
                    help="also remove directive comments (@ts-ignore, noqa, yaml-language-server, ...)")
    ap.add_argument("--sql-bodies", action="store_true",
                    help="also strip comments inside $$ bodies of LANGUAGE sql/plpgsql functions")
    ap.add_argument("--selftest", action="store_true", help="run built-in tests and exit")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    names = [x.strip() for x in args.only.split(",")] if args.only else list(BY_NAME)
    skip = {x.strip() for x in args.skip.split(",")} if args.skip else set()
    unknown = [x for x in names + list(skip) if x not in BY_NAME]
    if unknown:
        ap.error(f"unknown type(s): {', '.join(unknown)} (choose from {', '.join(BY_NAME)})")
    enabled = {n: BY_NAME[n] for n in names if n not in skip}
    opts = Options(strip_directives=args.strip_directives, sql_bodies=args.sql_bodies)

    changed: list[Path] = []
    failed: list[tuple[Path, str]] = []
    per_lang: Counter = Counter()
    total_removed = scanned = 0

    for path, lang in iter_files(args.paths, enabled, DEFAULT_EXCLUDES + args.exclude):
        scanned += 1
        try:
            old, new, removed = process_file(path, lang, opts)
        except (LexError, OSError) as e:
            failed.append((path, str(e)))
            continue
        if old == new:
            continue
        changed.append(path)
        total_removed += removed
        per_lang[lang.name] += removed
        print(f"{'rewrote' if args.write else 'would change'}: {path}  [{lang.name}]  (-{removed} comments)")
        if args.diff:
            sys.stdout.writelines(difflib.unified_diff(
                old.decode("utf-8").splitlines(True), new.decode("utf-8").splitlines(True),
                f"a/{path}", f"b/{path}"))
        if args.write:
            atomic_write(path, new)

    verb = "rewrote" if args.write else "would change"
    print(f"\nscanned {scanned} files, {verb} {len(changed)}, "
          f"{total_removed} comments {'removed' if args.write else 'found'}")
    if per_lang:
        print("by type: " + ", ".join(f"{k}={v}" for k, v in sorted(per_lang.items())))
    for note in sorted(NOTES):
        print(f"note: {note}", file=sys.stderr)
    for path, why in failed:
        print(f"SKIPPED {path}: {why}", file=sys.stderr)
    if not args.write and changed:
        print("dry run: re-run with --write to apply")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())