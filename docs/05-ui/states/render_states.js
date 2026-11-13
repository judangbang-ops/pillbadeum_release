// TASK-044 상태 그림(이 폴더의 *.dc.html)을 그려 PNG로 남기고 잼. Playwright가 깔린 곳에서 돌림.
// 실행: node docs/05-ui/states/render_states.js <page-check.js 경로>
//   - page-check.js는 corthex-uxui 스킬의 scripts/page-check.js
//   - 환경 변수 DPR: PNG 배율(기본 2). 재는 값은 배율과 상관없음
// 결과: 이 폴더의 <그림>.png, measure.json, 화면에 요약
// 재는 것은 그림 안의 data-v 묶음(위젯 하나, 화면 하나, 잠금 화면 칸 하나)마다:
//   1. 잘린 글자: 넘침을 숨기는 상자보다 넓거나 긴 글자(말줄임 '…', 줄 수 제한). 스크롤 목록(.list)은 뺌
//   2. 틀 넘침: 위젯·화면 틀보다 내용이 길어 아래가 잘림
//   3. 목록: 스크롤 목록(.list)의 높이, 내용 높이, 다 보이는 칸 수와 일부만 보이는 칸 수, 머리글·안내 줄 높이
//   4. 칸 둘째 줄에서 끊기지 않는 가장 긴 묶음(줄바꿈 없는 빈칸으로 이은 날짜·시각)의 폭과 칸 글자 폭
// 그리고 page-check.js의 오류·경고(명암비, 누르는 곳 크기 등)를 그림마다 셈
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const dir = __dirname;
const pageCheckPath = process.argv[2];
if (!pageCheckPath) {
  console.error('usage: node render_states.js <page-check.js>');
  process.exit(2);
}
const pageCheck = fs.readFileSync(pageCheckPath, 'utf8');
const dpr = Number(process.env.DPR || 2);

function compose(file) {
  const src = fs.readFileSync(path.join(dir, file), 'utf8');
  const helmet = src.match(/<helmet>([\s\S]*?)<\/helmet>/)[1];
  const body = src.match(/<\/helmet>([\s\S]*?)<\/x-dc>/)[1];
  const props = JSON.parse(src.match(/data-props='([^']*)'/)[1]);
  const { width } = props.$preview;
  const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${file}</title>
<base href="file://${dir}/">${helmet}</head><body><div id="frame" style="width:${width}px">${body}</div></body></html>`;
  return { html, width };
}

const measureInPage = () => {
  const short = (s) => s.replace(/\s+/g, ' ').trim().slice(0, 40);
  const out = [];
  for (const v of document.querySelectorAll('[data-v]')) {
    const r = { v: v.dataset.v, clipped: [], frameCut: null, list: null };
    for (const el of v.querySelectorAll('*')) {
      if (!el.checkVisibility() || el.classList.contains('list') || !el.textContent.trim()) continue;
      const s = getComputedStyle(el);
      if (s.overflowX !== 'visible' && el.scrollWidth > el.clientWidth + 1) r.clipped.push({ axis: 'x', text: short(el.textContent) });
      else if (s.overflowY !== 'visible' && el.scrollHeight > el.clientHeight + 1) r.clipped.push({ axis: 'y', text: short(el.textContent) });
    }
    // the frame: widget (.w), screen (.s) or lock screen cell, each hides what does not fit
    const frame = v.matches('.w, .s') ? v : v.firstElementChild;
    if (frame && frame.scrollHeight > frame.clientHeight + 1) r.frameCut = { height: frame.clientHeight, content: frame.scrollHeight };
    // the widget's top line (header or message line) and the longest piece of a tile's second line that
    // cannot break (a no-break space joins a date and its time), against the room it has
    const head = v.querySelector('.hd, .msg');
    if (head) r.head = Math.round(head.getBoundingClientRect().height);
    for (const s of v.querySelectorAll('.sub')) {
      for (const unit of s.textContent.split(' ').filter(Boolean)) {
        const probe = document.createElement('span');
        probe.style.whiteSpace = 'nowrap';
        probe.textContent = unit;
        s.appendChild(probe);
        const width = probe.getBoundingClientRect().width;
        s.removeChild(probe);
        if (!r.longestUnit || width - s.clientWidth > r.longestUnit.over) {
          r.longestUnit = { unit, width: Math.round(width), room: s.clientWidth, over: Math.round(width - s.clientWidth) };
        }
      }
    }
    const list = v.querySelector('.list');
    if (list) {
      const lr = list.getBoundingClientRect();
      const tiles = [...list.querySelectorAll('[role=button]')].map((t) => t.getBoundingClientRect());
      const full = tiles.filter((q) => q.top >= lr.top - 0.5 && q.bottom <= lr.bottom + 0.5).length;
      const seen = tiles.filter((q) => q.top < lr.bottom - 0.5 && q.bottom > lr.top + 0.5).length;
      r.list = { height: Math.round(lr.height), content: list.scrollHeight, tiles: tiles.length, full, partly: seen - full };
    }
    out.push(r);
  }
  return out;
};

(async () => {
  const browser = await chromium.launch({ proxy: process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined });
  const results = [];
  for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.dc.html')).sort()) {
    const c = compose(file);
    const ctx = await browser.newContext({ viewport: { width: c.width + 16, height: 1400 }, deviceScaleFactor: dpr, locale: 'ko-KR' });
    const page = await ctx.newPage();
    const tmp = path.join(require('os').tmpdir(), file.replace('.dc.html', '.html'));
    fs.writeFileSync(tmp, c.html);
    await page.goto('file://' + tmp, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    const fonts = await page.evaluate(() => [...document.fonts].filter((f) => f.status === 'loaded').length);
    const variants = await page.evaluate(measureInPage);
    const report = await page.evaluate(pageCheck);
    const name = file.replace('.dc.html', '');
    await page.locator('#frame').screenshot({ path: path.join(dir, name + '.png') });
    results.push({
      board: name, fontsLoaded: fonts, pageCheck: { error: report.counts.error, warn: report.counts.warn },
      issues: report.issues.filter((i) => i.level !== 'info').map((i) => `${i.level} ${i.check}: ${i.msg} "${i.text}"`),
      variants,
    });
    await ctx.close();
    fs.unlinkSync(tmp);
  }
  await browser.close();
  fs.writeFileSync(path.join(dir, 'measure.json'), JSON.stringify(results, null, 1) + '\n');
  for (const r of results) {
    console.log(`${r.board}: fonts ${r.fontsLoaded}, page-check error ${r.pageCheck.error} warn ${r.pageCheck.warn}`);
    for (const i of r.issues) console.log('   ' + i);
    for (const v of r.variants) {
      const list = v.list ? ` head ${v.head}px, list ${v.list.height}/${v.list.content}px, tiles full ${v.list.full} partly ${v.list.partly} of ${v.list.tiles}, longest unit ${v.longestUnit.width}/${v.longestUnit.room}px` : '';
      const cut = v.frameCut ? ` FRAME CUT ${v.frameCut.height}/${v.frameCut.content}px` : '';
      const clip = v.clipped.length ? ` CLIPPED ${v.clipped.map((c) => `${c.axis}:"${c.text}"`).join(', ')}` : '';
      console.log(`   ${v.v.padEnd(18)}${list}${cut}${clip}`);
    }
  }
})();
