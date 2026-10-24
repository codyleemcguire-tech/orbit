#!/usr/bin/env python3
"""Converts simple SVG icons (paths only) to Android vector drawables, drawn in white
so code can tint them. Usage: svg_to_vector.py in.svg out.xml"""
import re, sys, xml.etree.ElementTree as ET

src, out = sys.argv[1], sys.argv[2]
root = ET.parse(src).getroot()
vb = [float(v) for v in (root.get('viewBox') or '0 0 24 24').split()]
paths = []
for el in root.iter():
    if el.tag.split('}')[-1] != 'path':
        continue
    d = el.get('d')
    fill = el.get('fill', root.get('fill', '#000'))
    stroke = el.get('stroke')
    attrs = [f'android:pathData="{d}"']
    if fill and fill != 'none':
        attrs.append('android:fillColor="#FFFFFFFF"')
        if el.get('fill-rule') == 'evenodd' or el.get('clip-rule') == 'evenodd':
            attrs.append('android:fillType="evenOdd"')
    if stroke and stroke != 'none':
        attrs.append('android:strokeColor="#FFFFFFFF"')
        attrs.append(f'android:strokeWidth="{el.get("stroke-width", "1")}"')
        if el.get('stroke-linecap'):
            attrs.append(f'android:strokeLineCap="{el.get("stroke-linecap")}"')
        if el.get('stroke-linejoin'):
            attrs.append(f'android:strokeLineJoin="{el.get("stroke-linejoin")}"')
    paths.append('    <path\n        ' + '\n        '.join(attrs) + ' />')
xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Converted from NuvioTV's {src.split("/")[-1]} (NuvioMedia/NuvioTV, GPL-3.0). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{vb[2]:g}"
    android:viewportHeight="{vb[3]:g}">
''' + '\n'.join(paths) + '\n</vector>\n'
open(out, 'w').write(xml)
