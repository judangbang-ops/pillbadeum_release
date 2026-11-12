// 09 목업 보드(*.dc.html)를 캔버스 실행기(support.js) 없이 그려서 잼. Playwright가 깔린 곳에서 돌림.
// 실행: node docs/09-mockup/boards/measure_boards.js <보드 폴더> <결과 폴더> <page-check.js 경로> [default,200]
//   - page-check.js는 corthex-uxui 스킬의 scripts/page-check.js
//   - 환경 변수 UNROLL=1: 앱 화면(.s)은 휴대폰에서 스크롤되므로 내용 길이만큼 늘려 아래로 잘림을 빼고 잼.
//     위젯(BoxWidget*)과 알림 보드는 크기를 그대로 둠
//   - 환경 변수 FONT_OVERRIDE="Gothic A1": 다른 Google Fonts 글꼴로 바꿔 그려 봄(글꼴 폭이 달라도 버티는지)
// 보드마다 {{...}}를 그 보드의 renderVals()로 채움(속성은 기본값, 글자 크기만 실행마다 바꿈). 재는 것:
//   1. page-check.js의 오류·경고  2. 보드 왼쪽·오른쪽 끝을 넘는 글자  3. 보드 끝을 넘는 버튼·칸·테두리 상자
//   4. 넘침을 숨기는 상자에 옆으로 잘린 글자  5. 아래로 잘린 상자(내용이 상자보다 김)
// 결과: <결과 폴더>/results.json, 실행마다 .html과 .png(배율 1)
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const [, , boardsDir, outDir, pageCheckPath, runsArg = 'default,200'] = process.argv;
if (!boardsDir || !outDir || !pageCheckPath) {
  console.error('usage: node measure_boards.js <boards dir> <out dir> <page-check.js> [default,200]');
  process.exit(2);
}
const pageCheck = fs.readFileSync(pageCheckPath, 'utf8');
fs.mkdirSync(outDir, { recursive: true });

class DCLogic { constructor(props) { this.props = props; } }

function compose(file, scale) {
  const src = fs.readFileSync(path.join(boardsDir, file), 'utf8');
  const helmet = src.match(/<helmet>([\s\S]*?)<\/helmet>/)[1];
  const body = src.match(/<\/helmet>([\s\S]*?)<\/x-dc>/)[1];
  const script = src.match(/<script type="text\/x-dc"[^>]*data-props='([^']*)'>([\s\S]*?)<\/script>/);
  const propsDef = JSON.parse(script[1]);
  const props = {};
  for (const [k, v] of Object.entries(propsDef)) if (!k.startsWith('$') && v && 'default' in v) props[k] = v.default;
  if (scale !== 'default') {
    if (!('fontScale' in propsDef)) return null;
    if (props.fontScale === scale) return null; // same as the default run
    props.fontScale = scale;
  }
  const Component = new Function('DCLogic', script[2] + '\nreturn Component;')(DCLogic);
  const inst = new Component(props);
  const vals = typeof inst.renderVals === 'function' ? inst.renderVals() : {};
  const filled = body.replace(/\{\{(\w+)\}\}/g, (m, k) => (k in vals ? String(vals[k]) : m));
  const left = filled.match(/\{\{\w+\}\}/g);
  const { width, height } = propsDef.$preview || { width: 360, height: 800 };
  // App screens scroll on a phone, so in 'unroll' mode the screen grows to its content and only
  // sideways problems remain. Widgets (BoxWidget*) and the notification board keep their fixed size.
  const appScreen = /<div[^>]*class="s[ "]/.test(filled) && !file.startsWith('BoxWidget');
  const unroll = process.env.UNROLL === '1' && appScreen;
  let extra = unroll ? '<style>.s{height:auto!important;min-height:800px}.s .body{overflow:visible!important}</style>' : '';
  if (process.env.FONT_OVERRIDE) {
    const fam = process.env.FONT_OVERRIDE;
    extra += `<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=${encodeURIComponent(fam).replace(/%20/g, '+')}:wght@400;600;800&display=swap"><style>.s,.w,.sys{font-family:'${fam}',sans-serif!important}</style>`;
  }
  const frameH = unroll ? 'auto' : height + 'px';
  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${file}</title>
<base href="file://${path.resolve(boardsDir)}/">${helmet}${extra}</head>
<body><div id="frame" style="width:${width}px;height:${frameH};position:relative">${filled}</div></body></html>`;
  return { html, width, height, props, left, unroll };
}

const measureInPage = () => {
  const frame = document.getElementById('frame').getBoundingClientRect();
  const out = { edge: [], clipX: [], cutY: [] };
  const walker = document.createTreeWalker(document.getElementById('frame'), NodeFilter.SHOW_TEXT);
  const seen = new Set();
  const label = (el) => {
    let s = el.localName + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : '');
    return s;
  };
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const text = n.textContent.replace(/\s+/g, ' ').trim();
    if (!text) continue;
    const el = n.parentElement;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const r = document.createRange(); r.selectNodeContents(n);
    const rects = [...r.getClientRects()].filter((x) => x.width > 0 && x.height > 0);
    if (!rects.length) continue;
    // hidden by a display:none ancestor
    if (!el.checkVisibility()) continue;
    for (const q of rects) {
      if (q.right > frame.right + 0.5 || q.left < frame.left - 0.5) { out.edge.push({ text: text.slice(0, 30), el: label(el), right: Math.round(q.right - frame.left) }); break; }
    }
    for (let a = el; a && a.id !== 'frame'; a = a.parentElement) {
      const s = getComputedStyle(a);
      const ox = s.overflowX;
      const b = a.getBoundingClientRect();
      if (ox !== 'visible') {
        const bl = b.left + parseFloat(s.borderLeftWidth), br = b.right - parseFloat(s.borderRightWidth);
        if (rects.some((q) => q.right > br + 1 || q.left < bl - 1)) {
          const key = label(a) + '|' + text.slice(0, 30);
          if (!seen.has(key)) { seen.add(key); out.clipX.push({ text: text.slice(0, 30), box: label(a), boxWidth: Math.round(b.width), over: Math.round(Math.max(...rects.map((q) => q.right)) - br) }); }
          break;
        }
      }
    }
  }
  // boxes that show (buttons, fields, filled or bordered boxes) past the board's left or right edge
  out.boxEdge = [];
  for (const a of document.querySelectorAll('#frame *')) {
    if (!a.checkVisibility()) continue;
    const s = getComputedStyle(a);
    const shows = a.matches('button, input, select, textarea, [role=button]') || (s.backgroundColor !== 'rgba(0, 0, 0, 0)' && s.backgroundColor !== 'transparent') || parseFloat(s.borderLeftWidth) > 0 || parseFloat(s.borderRightWidth) > 0;
    if (!shows) continue;
    const q = a.getBoundingClientRect();
    if (q.width === 0 || q.height === 0) continue;
    if (q.right > frame.right + 0.5 || q.left < frame.left - 0.5) out.boxEdge.push({ box: label(a), text: a.textContent.replace(/\s+/g, ' ').trim().slice(0, 20), left: Math.round(q.left - frame.left), right: Math.round(q.right - frame.left) });
  }
  for (const a of document.querySelectorAll('#frame *')) {
    const s = getComputedStyle(a);
    if ((s.overflowY === 'hidden' || s.overflowY === 'clip') && a.scrollHeight > a.clientHeight + 1 && a.clientHeight > 0 && a.checkVisibility()) {
      out.cutY.push({ box: label(a), height: a.clientHeight, content: a.scrollHeight });
    }
  }
  return out;
};

(async () => {
  const browser = await chromium.launch({ proxy: process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined });
  const ctx = await browser.newContext({ viewport: { width: 400, height: 1400 }, deviceScaleFactor: 1, locale: 'ko-KR' });
  const files = fs.readdirSync(boardsDir).filter((f) => f.endsWith('.dc.html')).sort();
  const runs = runsArg.split(',');
  const results = [];
  for (const file of files) {
    for (const scale of runs) {
      const c = compose(file, scale);
      if (!c) continue;
      const tmp = path.join(outDir, `${file.replace('.dc.html', '')}-${scale}.html`);
      fs.writeFileSync(tmp, c.html);
      const page = await ctx.newPage();
      await page.goto('file://' + path.resolve(tmp), { waitUntil: 'networkidle' });
      await page.evaluate(() => document.fonts.ready);
      const fonts = await page.evaluate(() => [...document.fonts].filter((f) => f.status === 'loaded').map((f) => f.family.replace(/"/g, '') + ' ' + f.weight).filter((v, i, a) => a.indexOf(v) === i));
      const m = await page.evaluate(measureInPage);
      const report = await page.evaluate(pageCheck);
      const errors = report.issues.filter((i) => i.level === 'error').map((i) => `${i.check}: ${i.msg} @ ${i.where} "${i.text}"`);
      const warns = report.issues.filter((i) => i.level === 'warn').map((i) => `${i.check}: ${i.msg} "${i.text}"`);
      const fh = await page.evaluate(() => document.getElementById('frame').getBoundingClientRect().height);
      await page.screenshot({ path: tmp.replace('.html', '.png'), clip: { x: 8, y: 8, width: c.width, height: Math.ceil(fh) }, fullPage: true });
      results.push({ unroll: c.unroll, frameHeight: Math.round(fh), board: file.replace('.dc.html', ''), scale: scale === 'default' ? (c.props.fontScale || 'n/a') : scale, unfilled: c.left, fontsLoaded: fonts.length, errors: report.counts.error, warns: report.counts.warn, edge: m.edge, boxEdge: m.boxEdge, clipX: m.clipX, cutY: m.cutY, errorList: errors, warnList: warns });
      await page.close();
    }
  }
  await browser.close();
  fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(results, null, 1));
  for (const r of results) {
    console.log(`${r.board.padEnd(14)} ${String(r.scale).padEnd(4)} ${r.unroll ? 'unrolled ' + String(r.frameHeight).padEnd(5) : 'fixed          '} err ${r.errors} warn ${r.warns} edge ${r.edge.length} boxEdge ${r.boxEdge.length} clipX ${r.clipX.length} cutY ${r.cutY.length} fonts ${r.fontsLoaded}${r.unfilled ? ' UNFILLED ' + r.unfilled : ''}`);
  }
})();
