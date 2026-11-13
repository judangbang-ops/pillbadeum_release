"""Writes the TASK-044 state drawings (*.dc.html) next to this file, in the 09 board format.

- WidgetSheet: SCR-001 at the real 4x2 size (350x213dp), four across (the layout of the current
  DidITakeWidget.kt) next to the 09 day-box layout, at font 100% and 200%, with and without the message line.
- LockSheet: SCR-003 lock screen eight cells, drawn from the SPEC-013 methods (PR #15 experiment).
- JudgeSheet: SCR-007 for a past window at 360dp, font 100% and 200%.

Look: ../../09-mockup/boards/tokens.css (from design/base.tokens.json) and box.css, as in the 09 boards.
Run: python3 docs/05-ui/states/build_states.py, then render_states.js (see README.md in this folder).
"""
import json, os

P = os.path.dirname(os.path.abspath(__file__))
LOOK = '../../09-mockup/boards/'


def board(name, title, body, css, w, h):
    """One drawing in the 09 board format. No props: every state is drawn side by side on the sheet."""
    props = {"$preview": {"width": w, "height": h}}
    html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;600;800&amp;family=Roboto:wght@400;600;800&amp;display=swap">
<link rel="stylesheet" href="{LOOK}tokens.css">
<link rel="stylesheet" href="{LOOK}box.css">
<style>
{css.strip()}
</style>
</helmet>
{body.strip()}
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{json.dumps(props, ensure_ascii=False)}'>
class Component extends DCLogic {{}}
</script>
</body>
</html>
'''
    with open(os.path.join(P, name + '.dc.html'), 'w', encoding='utf-8') as f:
        f.write(html)


def ic(d, size=24, sw=2.2):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" aria-hidden="true"><path d="{d}" fill="none" '
            f'stroke="currentColor" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/></svg>')


BACK = ic('M15 5l-7 7 7 7')
CHECK = ('<svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><path d="M3 7.3l2.6 2.6L11 4.5" fill="none" '
         'stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>')
MINUS = ('<svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 6h6" stroke="currentColor" '
         'stroke-width="2.2" stroke-linecap="round"/></svg>')
DOWN = ('<svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 4l4 4 4-4" fill="none" '
        'stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>')
NB = ' '  # 날짜와 시각 사이: 줄이 바뀌어도 '11/10(화) 21:40'이 갈라지지 않게

SHEET_CSS = '''
.sheet{box-sizing:border-box;padding:var(--space-xl);background:var(--color-surface-sunken);color:var(--color-ink-default);
  font-family:Roboto,'Noto Sans KR',var(--font-family-base);font-size:var(--font-size-body);word-break:keep-all;overflow-wrap:break-word}
.sheet *{box-sizing:border-box}
.sheet p{margin:0}
.sheet .st{font-size:var(--font-size-title-md);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight)}
.sheet .sd{margin:var(--space-xs) 0 var(--space-xl);font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal);color:var(--color-ink-muted)}
.sheet figure{margin:0}
.sheet figcaption{font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold);line-height:var(--font-line-height-tight);margin-bottom:var(--space-xs)}
'''

# ------------------------------------------------------------------------------------------------
# SCR-001 위젯: 가로 4개 배치와 요일 약통 배치 (4×2, 글자 100%·200%, 안내 줄 없음·있음)
# 항목은 가장 긴 문구가 나오게 고름: 복용, 건너뜀, 매일 약 미완료 + 그보다 늦은 건너뜀(S-2 둘째 줄),
# 주 1회 미완료(W-2, 이름과 같은 크기). 머리글 수는 S-1: 복용 1, 건너뜀 1, 항목 4
ITEMS = [
    dict(p='p1', who='나', name='아침약', state='done', sub=['08:02 먹음'],
         label='나 아침약, 복용함, 오늘 08:02'),
    dict(p='p1', who='나', name='비타민D', state='skip', sub=['건너뜀'],
         label='나 비타민D, 건너뜀'),
    dict(p='p2', who='엄마', name='혈압약', state='todo', sub=[f'마지막 복용 11/10(화){NB}21:40', '어제 건너뜀'],
         label='엄마 혈압약, 미완료, 마지막 복용 11월 10일 화요일 21:40, 어제 건너뜀'),
    dict(p='p2', who='엄마', name='골다공증약', state='todo', week=True, sub=[f'마지막 복용 11/02(월){NB}08:10'],
         label='엄마 골다공증약, 이번 주 미완료, 마지막 복용 11월 2일 월요일 08:10'),
]
# S-3: 건너뜀 칸을 누르면 기록하지 않고 보이는 문구. 위젯 안내 줄 가운데 가장 긺
MESSAGE = '비타민D: 건너뜀으로 기록됨 (09:10). 바꾸려면 앱에서'
HEADER = ('<div class="hd"><span class="dow">목</span><span class="date">필받음 · 11/12</span>'
          '<span class="cnt"><b>1 / 4</b> 완료 · 건너뜀 1</span></div>')
# 목록이 창보다 길어 아래 화살표를 보이는 그림. render_states.js가 잰 목록 높이로 정함(README.md 2장)
OVERFLOW = {'four-100': False, 'four-200': True, 'four-100-msg': False, 'four-200-msg': True,
            'box-100': True, 'box-200': True, 'box-100-msg': True, 'box-200-msg': True}


def icon(state):
    return CHECK if state == 'done' else MINUS if state == 'skip' else ''


def box_tile(it):
    subs = ''.join(f'<span class="sub{" wk" if it.get("week") else ""}">{s}</span>' for s in it['sub'])
    # 상태 원은 이름 줄 오른쪽에만 둠: 그 아래 줄(마지막 복용)은 칸 폭을 다 씀 (09 BoxWidget은 칸 전체에 오른쪽 여백 38px)
    return (f'<div role="button" tabindex="0" class="t {it["p"]} {it["state"]}" aria-label="{it["label"]}">'
            f'<span class="tr"><span class="nm"><span class="who">{it["who"]}</span>{it["name"]}</span>'
            f'<span class="ic" aria-hidden="true">{icon(it["state"])}</span></span>{subs}</div>')


def four_tile(it):
    # 지금 코드(Pill): 원 위에 대상자 배지, 이름 maxLines 1, 보조 문구 maxLines 2
    return (f'<div role="button" tabindex="0" class="q {it["p"]} {it["state"]}" aria-label="{it["label"]}">'
            f'<span class="c" aria-hidden="true">{icon(it["state"])}<i class="bd">{it["who"][0]}</i></span>'
            f'<span class="nm">{it["name"]}</span>'
            f'<span class="sub{" wk" if it.get("week") else ""}">{" ".join(it["sub"])}</span></div>')


def widget(layout, big, msg):
    v = f'{layout}-{"200" if big else "100"}{"-msg" if msg else ""}'
    if layout == 'box':
        cols, tiles = ('minmax(0,1fr)' if big else 'repeat(2,minmax(0,1fr))'), ''.join(box_tile(it) for it in ITEMS)
    else:
        cols, tiles = 'repeat(4,minmax(0,1fr))', ''.join(four_tile(it) for it in ITEMS)
    head = f'<div class="msg">{MESSAGE}</div>' if msg else HEADER
    more = f'<div class="more" role="img" aria-label="더 있음">{DOWN}</div>' if OVERFLOW[v] else ''
    return (f'<div data-v="{v}" class="w{" fs200" if big else ""}">{head}<div class="list">'
            f'<div class="grid" style="grid-template-columns:{cols}">{tiles}</div></div>{more}</div>')


ROWS = [('four', False, '가로 4개 (지금 코드의 배치)'), ('four', True, '가로 4개 · 안내 줄이 보이는 10초'),
        ('box', False, '요일 약통 (09 목업의 배치)'), ('box', True, '요일 약통 · 안내 줄이 보이는 10초')]
cells = ''.join(
    f'<figure><figcaption>{name} · 글자 {"200% (가장 큼)" if big else "100%"}</figcaption>{widget(layout, big, msg)}</figure>'
    for layout, msg, name in ROWS for big in (False, True))
board('WidgetSheet', 'TASK-044 SCR-001 위젯 두 배치 (4×2, 글자 100%·200%)', f'''
<div class="sheet" style="width: 772px">
  <p class="st">SCR-001 위젯: 가로 4개와 요일 약통 (4×2 = 350×213dp)</p>
  <p class="sd">위젯 크기는 그대로이고 글자만 커짐(Android 14 비선형 글자 크기, tokens.css의 .fs200). 안내 줄은 머리글 자리에 10초 동안 보임. 목록만 스크롤되고, 가려진 칸이 있으면 아래 가운데에 화살표</p>
  <div class="cols">{cells}</div>
</div>''', css=SHEET_CSS + '''
.cols{display:grid;grid-template-columns:350px 350px;gap:var(--space-xl) var(--space-2xl)}
.w{width:350px;height:213px;padding:10px 12px;border-radius:var(--radius-widget);background:var(--color-surface-default);
  box-shadow:0 0 0 1px var(--color-line-subtle);font-size:var(--font-size-body-sm);color:var(--color-ink-default);
  font-variant-numeric:tabular-nums;display:flex;flex-direction:column;gap:var(--space-xs);overflow:hidden;position:relative}
.hd{display:flex;flex-wrap:wrap;align-items:center;column-gap:var(--space-sm);row-gap:2px;padding:0 var(--space-2xs);flex-shrink:0}
.w .dow{width:auto;height:auto;min-width:28px;min-height:28px;padding:0 var(--space-xs);line-height:1.2;border-radius:var(--radius-cell);font-size:var(--font-size-body-sm)}
.date{font-weight:var(--font-weight-semibold)}
.cnt{margin-left:auto;color:var(--color-ink-muted);white-space:nowrap}
.cnt b{color:var(--color-ink-default);font-weight:var(--font-weight-extrabold)}
.msg{flex-shrink:0;border-radius:var(--radius-button);padding:var(--space-xs) var(--space-md);background:var(--color-surface-notice);
  color:var(--color-ink-notice);font-weight:var(--font-weight-semibold);line-height:var(--font-line-height-normal)}
.list{flex-grow:1;min-height:0;overflow:hidden}
.grid{display:grid;gap:var(--space-xs);align-content:start}
/* 요일 약통 칸: 09 BoxWidget과 같음. 주 1회 마지막 복용은 W-2대로 이름과 같은 크기 */
.t{border-radius:var(--radius-tile);padding:var(--space-sm) var(--space-sm) var(--space-sm) var(--space-md);display:flex;flex-direction:column;
  justify-content:center;gap:2px;min-height:64px;background:var(--p-tint);color:var(--p-ink)}
.t .tr{display:flex;align-items:flex-start;gap:var(--space-xs)}
.t .who{font-weight:var(--font-weight-semibold);margin-right:5px;font-size:var(--font-size-body-sm)}
.t .nm{flex-grow:1;min-width:0;font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight);font-size:var(--font-size-body)}
.t .sub{line-height:var(--font-line-height-tight)}
.t .sub.wk{font-size:var(--font-size-body);font-weight:var(--font-weight-semibold)}
.t .ic{flex-shrink:0;width:24px;height:24px;border-radius:50%;display:flex;align-items:center;justify-content:center}
.t.todo .ic{border:var(--border-focus) dashed currentColor}
.t.done{background:var(--p-solid);color:var(--color-ink-on-solid)}
.t.done .ic{background:var(--color-ink-on-solid);color:var(--p-solid)}
.t.skip{background:var(--color-surface-skip);color:var(--color-ink-muted)}
.t.skip .ic{background:var(--color-line-strong);color:var(--color-ink-on-solid)}
/* 가로 4개: 지금 코드(DidITakeWidget.kt Pill)의 배치를 09 토큰 글자 크기로 그림 */
.q{position:relative;border-radius:var(--radius-tile);padding:var(--space-sm) var(--space-2xs);display:flex;flex-direction:column;align-items:center;
  text-align:center;gap:2px;min-width:0;background:var(--p-tint);color:var(--p-ink)}
.q .c{width:40px;height:40px;border-radius:50%;display:flex;align-items:center;justify-content:center;position:relative;flex-shrink:0;border:var(--border-focus) dashed currentColor}
.q.done{background:var(--p-solid);color:var(--color-ink-on-solid)}
.q.done .c{border:0;background:var(--color-ink-on-solid);color:var(--p-solid)}
.q.skip{background:var(--color-surface-skip);color:var(--color-ink-muted)}
.q.skip .c{border:0;background:var(--color-line-strong);color:var(--color-ink-on-solid)}
.q .bd{position:absolute;left:-8px;top:-6px;min-width:20px;padding:0 3px;border-radius:6px;background:var(--p-solid);color:var(--color-ink-on-solid);
  font-size:var(--font-size-body-sm);font-style:normal;font-weight:var(--font-weight-extrabold);line-height:1.3;text-align:center;box-shadow:0 0 0 2px var(--color-surface-default)}
.q .nm{max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:var(--font-size-body);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight)}
.q .sub{max-width:100%;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:var(--font-line-height-tight)}
.q .sub.wk{font-size:var(--font-size-body);font-weight:var(--font-weight-semibold)}
.more{position:absolute;left:50%;bottom:0;transform:translateX(-50%);width:44px;height:18px;display:flex;align-items:center;justify-content:center;
  background:var(--color-surface-default);border-radius:9px 9px 0 0;box-shadow:0 -1px 0 var(--color-line-subtle);color:var(--color-ink-muted)}
''', w=772, h=1200)

# ------------------------------------------------------------------------------------------------
# SCR-003 잠금 화면 여덟 칸: 이름 표시 끔·켬 × 기기 '내용 표시'·'내용 숨김' × 잠금·잠금을 푼 알림창
# 방법은 04 SPEC-013(PR #15 실험의 방법 5와 1). 알림 모양은 기기(Pixel, Android 14 밝은 테마)가 그리므로
# 이 그림만 기기 색을 그대로 씀(09 Notif 보드와 같음). 앱이 정하는 것은 글자, 작은 아이콘, 강조색뿐
APP = '<span class="ai" aria-hidden="true">' + CHECK + '</span>'
ACTS = '<div class="na"><span>먹었어</span><span>30분 뒤</span><span>오늘 건너뜀</span></div>'
BODY = '마지막 복용 어제 08:02'


def folded(title, body=None):
    second = f'<small>{body}</small>' if body else ''
    return f'<div class="n f">{APP}<span class="tx"><b>{title}</b>{second}</span><span class="cv" aria-hidden="true">{DOWN}</span></div>'


def opened(title):
    return (f'<div class="n"><div class="nh">{APP}<span>필받음 · 지금</span></div>'
            f'<p class="nt">{title}</p><p class="nb">{BODY}</p>{ACTS}</div>')


def lock(cards):
    return f'<div class="ph lock"><p class="clk">8:00</p><p class="day">11월 12일 목요일</p>{cards}</div>'


def shade(cards):
    return f'<div class="ph shade">{cards}</div>'


GROUP = ('<div class="n g"><div class="nh">' + APP + '<span>필받음</span></div>'
         '<div class="ch"><p class="cn">지금</p><p class="nt">복용 시간이에요</p></div>'
         '<div class="ch"><p class="cn">지금</p><p class="nt">아침약 먹어~!</p><p class="nb">' + BODY + '</p>' + ACTS + '</div>'
         '<div class="ch"><p class="cn">지금</p><p class="nt">혈압약 먹어~!</p><p class="nb">' + BODY + '</p>' + ACTS + '</div></div>')
OFF_LOCK = lock(folded('복용 시간이에요'))
OFF_OPEN = shade(GROUP)
ON_SHOW_LOCK = lock(opened('아침약 먹어~!') + folded('혈압약 먹어~!', BODY))
ON_OPEN = shade(opened('아침약 먹어~!') + opened('혈압약 먹어~!'))
ON_HIDE_LOCK = lock(folded('복용 시간이에요') + folded('복용 시간이에요'))
OFF_LOCK_CAP = ("항목 알림은 SECRET이라 보이지 않음. 따로 띄운 PUBLIC 알림 하나: 제목 '복용 시간이에요'만, 본문·버튼 없음. "
                "누르면 잠금을 푼 뒤 앱(SCR-004)")
OFF_OPEN_CAP = "항목 알림마다 제목·본문·버튼 셋. '복용 시간이에요' 줄이 하나 더 보임(방법 5의 대가)"
LOCK_ROWS = [
    ('lock-off-show', "이름 표시 끔 (기본) · 기기 '내용 표시' (Android 기본)", OFF_LOCK, OFF_LOCK_CAP, OFF_OPEN, OFF_OPEN_CAP),
    ('lock-off-hide', "이름 표시 끔 (기본) · 기기 '내용 숨김'", OFF_LOCK, OFF_LOCK_CAP + '. 기기 설정과 상관없이 같음', OFF_OPEN, OFF_OPEN_CAP),
    ('lock-on-show', "이름 표시 켬 · 기기 '내용 표시'", ON_SHOW_LOCK,
     '항목 알림(PRIVATE)이 그대로 보임: 제목·본문, 펼치면 버튼 셋. 버튼은 잠금을 풀지 않고 기록함(화면 5장 6번)', ON_OPEN,
     '항목 알림마다 제목·본문·버튼 셋'),
    ('lock-on-hide', "이름 표시 켬 · 기기 '내용 숨김'", ON_HIDE_LOCK,
     "항목마다 공개용 판 '복용 시간이에요'(본문·버튼 없음). 기기 설정을 따름(SPEC-013)", ON_OPEN, '항목 알림마다 제목·본문·버튼 셋'),
]
lock_rows = ''.join(
    f'<p class="rt">{title}</p><div class="pair">'
    f'<figure><div data-v="{v}-locked" class="cellwrap">{a}</div><figcaption class="cap">{acap}</figcaption></figure>'
    f'<figure><div data-v="{v}-open" class="cellwrap">{b}</div><figcaption class="cap">{bcap}</figcaption></figure></div>'
    for v, title, a, acap, b, bcap in LOCK_ROWS)
board('LockSheet', 'TASK-044 SCR-003 잠금 화면 여덟 칸', f'''
<div class="sheet" style="width: 784px">
  <p class="st">SCR-003 잠금 화면 여덟 칸 (SPEC-013, REQ-046·REQ-076)</p>
  <p class="sd">이름 표시 끔·켬 × 기기의 잠금 화면 알림 설정 둘 × 잠금·잠금을 푼 알림창. 방법과 모양은 PR #15 실험(에뮬레이터 API 26·30·34·36, Pixel 화면)의 방법 5(끔)와 방법 1(켬) 결과를 옮김. 항목 알림 둘(아침약·혈압약)이 같은 시각에 뜬 때. 갤럭시(One UI)는 다를 수 있어 TASK-052에서 실기기로 봄</p>
  <div class="pair head"><p>잠금 화면</p><p>잠금을 푼 알림창</p></div>
  {lock_rows}
</div>''', css=SHEET_CSS + '''
.pair{display:grid;grid-template-columns:360px 360px;gap:var(--space-lg)}
.pair.head p{font-size:var(--font-size-label);font-weight:var(--font-weight-extrabold)}
.sheet .rt{margin:var(--space-xl) 0 var(--space-sm);font-size:var(--font-size-label);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight)}
.cap{margin:var(--space-xs) var(--space-2xs) 0!important;font-weight:var(--font-weight-regular)!important;line-height:var(--font-line-height-normal)!important;color:var(--color-ink-muted)}
.cellwrap{width:360px;height:380px;border-radius:var(--radius-tile);overflow:hidden}
/* 기기가 그리는 부분: 색과 크기는 Pixel 시스템 화면을 흉내 냄 */
.ph{width:360px;height:380px;font-family:Roboto,'Noto Sans KR',sans-serif;padding-bottom:12px}
.ph.lock{background:linear-gradient(180deg,#24395E 0%,#0E1424 62%);color:#DCE6F5}
.ph.shade{background:#E9EAEE;color:#1B1B1F;padding-top:12px}
.clk{font-size:56px;line-height:1;padding:28px 24px 6px;font-weight:400}
.day{font-size:14px;padding:0 24px 16px}
.n{margin:0 12px 4px;background:#F3F3F7;border-radius:20px;padding:12px 16px 4px;color:#1B1B1F}
.n.f{display:flex;align-items:center;gap:12px;padding:12px 12px 12px 16px}
.n .tx{display:flex;flex-direction:column;gap:2px;flex-grow:1;min-width:0}
.n .tx b{font-size:15px;font-weight:600}
.n .tx small{font-size:14px;color:#46464F}
.cv{width:28px;height:28px;border-radius:50%;background:#E3E3E9;display:flex;align-items:center;justify-content:center;color:#1B1B1F;flex-shrink:0}
.nh{display:flex;align-items:center;gap:8px;font-size:13px;color:#46464F}
.ai{width:24px;height:24px;border-radius:50%;background:var(--color-ink-default);color:var(--color-ink-on-solid);display:flex;align-items:center;justify-content:center;flex-shrink:0}
.nt{font-size:15px;font-weight:600;margin-top:8px!important}
.nb{font-size:14px;color:#46464F;margin-top:2px!important}
.na{display:flex;gap:4px;margin:0 -8px}
.na span{min-height:48px;display:flex;align-items:center;padding:0 8px;font-size:14px;font-weight:600;color:var(--color-ink-default)}
.n.g{padding-bottom:0}
.ch{border-top:1px solid #E3E3E9;margin:8px -16px 0;padding:6px 16px 0}
.ch .cn{font-size:12px;color:#46464F}
.ch .nt{margin-top:2px!important}
.ch:nth-of-type(2) .nt{padding-bottom:10px}
''', w=784, h=2200)

# ------------------------------------------------------------------------------------------------
# SCR-007 판정 고치기, 지난 창: 기록 없음·먹음·건너뜀(주 1회)·#locked, 폭 360dp 글자 100%·200%
JUDGES = [
    dict(v='none', cap='기록 없음 · 매일 약 · 어제', title=f'11/11{NB}(수) 기록{NB}고치기', p='p2', tile='', who='엄마', nm='혈압약',
         cyc='매일 · 저녁', win=f'11/11{NB}(수){NB}04:00 ~ 11/12{NB}(목){NB}04:00', st='기록이 없어요',
         acts=[('p', '먹은 시각 고르기'), ('line', '건너뜀')],
         note='먹은 시각은 11/11 (수) 04:00부터 11/12 (목) 04:00 전까지 고를 수 있어요.'),
    dict(v='taken', cap='먹음 · 매일 약 · 어제', title=f'11/11{NB}(수) 기록{NB}고치기', p='p1', tile='done', who='나', nm='아침약',
         cyc='매일 · 아침', win=f'11/11{NB}(수){NB}04:00 ~ 11/12{NB}(목){NB}04:00', st='08:10에 먹음',
         acts=[('line', '먹은 시각 고치기'), ('danger', '기록 취소')], note='기록을 취소해도 지워지지 않고 무효로 남아요.'),
    dict(v='skipped', cap='건너뜀 · 주 1회 약 · 지난주', title=f'11/02{NB}(월){NB}주 기록{NB}고치기', p='p2', tile='skip', who='엄마',
         nm='골다공증약', cyc='주 1회 · 월요일', win=f'11/02{NB}(월) ~ 11/08{NB}(일)', st='건너뜀',
         acts=[('p', '먹었어로 바꾸기'), ('danger', '건너뜀 취소')],
         note='먹었어로 바꾸면 먹은 시각을 먼저 골라요. 11/02 (월) 04:00부터 11/09 (월) 04:00 전까지예요.'),
    dict(v='locked', cap='#locked · 하루 시작 시각을 바꾸기 전에 끝난 날', title=f'11/01{NB}(일) 기록{NB}고치기', p='p1', tile='done',
         who='나', nm='아침약', cyc='매일 · 아침', win=f'11/01{NB}(일){NB}04:00 ~ 11/02{NB}(월){NB}04:00', st='08:30에 먹음', acts=[],
         band='설정을 바꾸기 전의 기록은 고칠 수 없어요.', note='하루 시작 시각이나 기준 시간대를 바꾸기 전에 끝난 날이에요.'),
]


def judge(j, big):
    acts = ''.join(f'<button class="btn {kind}" type="button">{text}</button>' for kind, text in j['acts'])
    band = f'<div class="band" style="margin-top: var(--space-xl)">{j["band"]}</div>' if j.get('band') else ''
    return (f'<div data-v="{j["v"]}-{"200" if big else "100"}" class="s{" fs200" if big else ""}">'
            f'<div class="bar back"><button class="ib" type="button" aria-label="뒤로">{BACK}</button><h1>{j["title"]}</h1></div>'
            f'<div class="body {j["p"]}"><div class="tile {j["tile"]}"><span class="who">{j["who"]}</span><span class="nm">{j["nm"]}</span>'
            f'<span class="sm">{j["cyc"]}</span><span class="win">{j["win"]}</span><span class="stt">{j["st"]}</span></div>'
            f'{band}<div class="acts">{acts}<p class="note">{j["note"]}</p></div></div></div>')


judge_cells = ''.join(
    f'<figure><figcaption>{j["cap"]} · 글자 {"200%" if big else "100%"}</figcaption>{judge(j, big)}</figure>'
    for j in JUDGES for big in (False, True))
board('JudgeSheet', 'TASK-044 SCR-007 지난 창의 판정 고치기 (360dp, 글자 100%·200%)', f'''
<div class="sheet" style="width: 784px">
  <p class="st">SCR-007 판정 고치기: 지난 창 (폭 360dp)</p>
  <p class="sd">지난 창에는 [지금 먹었어]가 없음. 시각은 연 창 안에서만 고름(보충 P-2). 화면 높이는 내용만큼 그림(휴대폰에서는 800dp를 넘으면 스크롤)</p>
  <div class="pair">{judge_cells}</div>
</div>''', css=SHEET_CSS + '''
.pair{display:grid;grid-template-columns:360px 360px;gap:var(--space-xl) var(--space-lg);align-items:start}
.sheet .s{height:auto;padding-bottom:var(--space-xl);border-radius:var(--radius-tile);box-shadow:0 0 0 1px var(--color-line-subtle)}
.sheet .s .body{overflow:visible}
.sheet .s .bar{padding-top:var(--space-xs);padding-bottom:var(--space-xs)}
.tile{border-radius:var(--radius-tile);padding:var(--space-lg);display:flex;flex-direction:column;gap:2px;background:var(--p-tint);color:var(--p-ink);margin-top:var(--space-sm)}
.tile.done{background:var(--p-solid);color:var(--color-ink-on-solid)}
.tile.skip{background:var(--color-surface-skip);color:var(--color-ink-muted)}
.tile .who{font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold)}
.tile .nm{font-size:var(--font-size-display-sm);font-weight:var(--font-weight-extrabold);line-height:1.3}
.tile .win{margin-top:var(--space-md);font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal)}
.tile .stt{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);margin-top:2px}
.acts{display:flex;flex-direction:column;gap:var(--space-sm);margin-top:var(--space-xl)}
.acts .note{margin-top:var(--space-2xs);padding:0 var(--space-2xs)}
''', w=784, h=4000)
print('written: WidgetSheet, LockSheet, JudgeSheet')
