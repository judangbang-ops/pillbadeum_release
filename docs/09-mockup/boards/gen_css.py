"""Run: python3 docs/09-mockup/boards/gen_css.py design/base.tokens.json docs/09-mockup/boards/tokens.css
design/base.tokens.json -> tokens.css (CSS custom properties named after the tokens).
.fs200 maps font sizes the way Android 14+ scales them at font scale 2.0 (nonlinear)."""
import json, sys
src, out = sys.argv[1], sys.argv[2]
t = json.load(open(src, encoding='utf-8'))
lines = []
def walk(node, path, typ):
    typ = node.get('$type', typ)
    if '$value' in node:
        v = node['$value']
        if typ == 'color': css = v['hex']
        elif typ == 'dimension': css = f"{v['value']}{v['unit']}"
        elif typ == 'fontFamily': css = ', '.join(f"'{x}'" if ' ' in x else x for x in v)
        else: css = str(v)
        lines.append(f"  --{'-'.join(path)}: {css};")
        return
    for k, child in node.items():
        if not k.startswith('$') and isinstance(child, dict):
            walk(child, path + [k], typ)
walk(t, [], None)
# Android 14 FontScaleConverter table for 2.0: sp 8,10,12,14,18,20,24,30,100 -> dp 16,20,24,26,30,34,36,38,100
xs = [8, 10, 12, 14, 18, 20, 24, 30, 100]; ys = [16, 20, 24, 26, 30, 34, 36, 38, 100]
def at200(sp):
    for i in range(len(xs) - 1):
        if xs[i] <= sp <= xs[i + 1]:
            return ys[i] + (sp - xs[i]) * (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i])
big = []
for name, tok in t['font']['size'].items():
    if name.startswith('$'): continue
    big.append(f"  --font-size-{name}: {round(at200(tok['$value']['value']), 1)}px;")
open(out, 'w', encoding='utf-8').write(
    "/* generated from design/base.tokens.json by gen_css.py. Do not edit. */\n:root {\n" + '\n'.join(lines) +
    "\n}\n/* font scale 200% (Android 14+, nonlinear) */\n.fs200 {\n" + '\n'.join(big) + "\n}\n")
print(len(lines), 'vars;', ' '.join(b.strip() for b in big))
