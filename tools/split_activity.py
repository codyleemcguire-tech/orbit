#!/usr/bin/env python3
"""Moves MainActivity functions into another file as MainActivity extension functions.

Usage: split_activity.py <MainActivity.kt> <target.kt> "<file comment>" name [name ...]

Every function with one of the names (all overloads) moves, with the comment lines just
above it, to the target file as `internal fun MainActivity.name(...)`. Properties stay in
MainActivity. Run once beforehand with --open to make MainActivity's private members
internal, so the moved functions can still reach them.
"""
import re
import sys

MEMBER = re.compile(
    r'^    (?:@[\w.]+(?:\([^)]*\))? )*(?:(?:private|internal|override|lateinit|inner|data|open|protected)\s+)*'
    r'(fun|val|var|class|object|data class|enum class|interface)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)')


def members(lines):
    """(start, end, kind, name): start includes the comments/annotations above; end exclusive."""
    found = []
    for i, line in enumerate(lines):
        m = MEMBER.match(line)
        if m:
            found.append([i, m.group(1), m.group(2)])
    spans = []
    for k, (i, kind, name) in enumerate(found):
        start = i
        while start > 0 and re.match(r'^    (//|@)', lines[start - 1]):
            start -= 1
        spans.append([start, i, kind, name])
    result = []
    for k, (start, decl, kind, name) in enumerate(spans):
        end = spans[k + 1][0] if k + 1 < len(spans) else len(lines)
        # The class's own closing brace isn't part of its last member.
        if k + 1 == len(spans):
            end = max(j for j, l in enumerate(lines) if l.startswith('}'))
        while end > start and lines[end - 1].strip() == '':
            end -= 1
        result.append((start, end, kind, name))
    return result


def open_members(path):
    text = open(path).read()
    text = re.sub(r'(?m)^(    (?:@[\w.]+(?:\([^)]*\))? )*)private ', r'\1internal ', text)
    open(path, 'w').write(text)


def move(src, target, comment, names):
    lines = open(src).read().split('\n')
    spans = [s for s in members(lines) if s[2] == 'fun' and s[3] in names]
    missing = set(names) - {s[3] for s in spans}
    if missing:
        sys.exit(f'not found: {sorted(missing)}')
    imports = [l for l in lines if l.startswith('import ')]
    # MainActivity's nested types (WatchTarget, …) by their short names.
    nested = sorted(set(re.findall(r'(?m)^    (?:(?:internal|private|inner|data|enum|abstract) )*(?:class|interface|object) ([A-Z]\w*)', '\n'.join(lines))))
    imports += [f'import com.orbit.player.MainActivity.{n}' for n in nested]
    moved = []
    for start, end, kind, name in spans:
        block = lines[start:end]
        out = []
        for l in block:
            l = l[4:] if l.startswith('    ') else l
            out.append(l)
        decl = next(j for j, l in enumerate(out) if re.match(r'^(@[\w.]+(\([^)]*\))? )*(private |internal )?fun ', l))
        out[decl] = re.sub(r'^((?:@[\w.]+(?:\([^)]*\))? )*)(?:private |internal )?fun (<[^>]+> )?',
                           lambda m: f"{m.group(1)}internal fun {m.group(2) or ''}MainActivity.", out[decl])
        body = '\n'.join(out).replace('this@MainActivity', f'this@{name}')
        moved.append(body)
    keep = []
    cut = set()
    for start, end, *_ in spans:
        cut.update(range(start, end))
    for i, l in enumerate(lines):
        if i not in cut:
            keep.append(l)
    # Collapse the blank runs left behind.
    text = re.sub(r'\n{3,}', '\n\n', '\n'.join(keep))
    open(src, 'w').write(text)
    header = ['package com.orbit.player', ''] + imports + ['', comment, '']
    existing = ''
    try:
        existing = open(target).read().rstrip() + '\n\n'
    except FileNotFoundError:
        existing = '\n'.join(header) + '\n'
    open(target, 'w').write(existing + '\n\n'.join(moved) + '\n')
    print(f'moved {len(spans)} functions to {target}')


if __name__ == '__main__':
    if sys.argv[1] == '--open':
        open_members(sys.argv[2])
    else:
        move(sys.argv[1], sys.argv[2], sys.argv[3], set(sys.argv[4:]))
