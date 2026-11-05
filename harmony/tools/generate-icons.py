#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
from pathlib import Path
import hashlib
import html
import json
import re

root = Path(__file__).resolve().parents[2]
source = root / 'android/lib/src/main/kotlin/com/phonedsh/ui/icon/PdshIcons.kt'
text = source.read_text()
out = root / 'harmony/entry/src/main/resources/rawfile/icons'
out.mkdir(parents=True, exist_ok=True)
tokens = json.loads((root / 'spec/ui/v1/tokens.json').read_text())
primary = tokens['colors']['textPrimary']
secondary = tokens['colors']['sessionSecondary']
variants = [(mode, suffix, role[mode])
            for suffix, role in [('', primary), ('-secondary', secondary)]
            for mode in ['light', 'dark']]
manifest = []
for match in re.finditer(r'PdshIcon\.(\w+)\s+to\s+listOf\(', text):
    name = match.group(1)
    cursor, depth, quoted, escaped = match.end(), 1, False, False
    start = cursor
    while depth and cursor < len(text):
        char = text[cursor]
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
        cursor += 1
    paths = re.findall(r'IconPath\("([^\"]+)"([^)]*)\)', text[start:cursor - 1])
    if not paths:
        raise RuntimeError(f'No vector paths for {name}')
    for mode, suffix, tint in variants:
        body = []
        for data, properties in paths:
            filled = bool(re.search(r'filled\s*=\s*true', properties))
            width = re.search(r'width\s*=\s*([\d.]+)f?', properties)
            if filled:
                attrs = f'fill="{tint}"'
            else:
                attrs = f'fill="none" stroke="{tint}" stroke-width="{width.group(1) if width else "1.8"}" stroke-linecap="round" stroke-linejoin="round"'
            body.append(f'<path d="{html.escape(data, quote=True)}" {attrs}/>')
        svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">\n' + '\n'.join(body) + '\n</svg>\n'
        (out / f'{name}{suffix}-{mode}.svg').write_text(svg)
    manifest.append(name)
(out / 'manifest.json').write_text(json.dumps({'source': str(source.relative_to(root)), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'icons': manifest}, indent=2) + '\n')
print(f'Generated {len(manifest)} Android vectors in light/dark and secondary-ink variants')
