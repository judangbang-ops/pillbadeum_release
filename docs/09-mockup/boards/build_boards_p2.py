"""Writes the 2차 첫 릴리즈 boards (*.dc.html) next to this file: 건강 카드 SCR-011~015 and the changed SCR-004·006·009·010.
Shared look: tokens.css + box.css, the same as the 1차 boards (build_boards.py, which this script does not run or change).
HomeCard* take the hand-written BoxHome.dc.html and add the [건강 카드] button. Examples are made up (가상정, 홍가상, 010-0000-0000).
Run: python3 docs/09-mockup/boards/build_boards_p2.py"""
import json, os, re
P = os.path.dirname(os.path.abspath(__file__))


def ic(d, size=24, sw=2.2):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" aria-hidden="true"><path d="{d}" fill="none" '
            f'stroke="currentColor" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/></svg>')


ICONS = {
    'CLOSE': ic('M6 6l12 12M18 6L6 18'), 'BACK': ic('M15 5l-7 7 7 7'), 'CHEV': ic('M9 5l7 7-7 7', 20, 2),
    'PLUS': ic('M12 5v14M5 12h14', 18, 2.4), 'CHECK': ic('M5 12.5l4.5 4.5L19 7.5', 18, 2.6),
    'OUT': ic('M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5', 18, 2),
    'CARD': ic('M4 6h16v12H4zM8 10h4M8 14h8', 20, 2), 'MAIL': ic('M4 6h16v12H4zM4 7l8 6 8-6', 18, 2),
}
FS = "class Component extends DCLogic {\n  renderVals() {\n    return { fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '' };\n  }\n}"
FSP = {"fontScale": {"editor": "enum", "options": ["100", "200"], "default": "100"}}


def fill(s):
    for k, v in ICONS.items():
        s = s.replace('@' + k + '@', v)
    return s


def board(name, title, body, css='', w=360, h=800, props=None, logic=None):
    props = dict(props if props is not None else FSP)
    props["$preview"] = {"width": w, "height": h}
    body = fill(body)
    if h > 800 and '<div class="s ' in body:
        body = body.replace('<div class="s ', f'<div style="height: {h}px" class="s ', 1)
    html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;600;800&amp;family=Roboto:wght@400;600;800&amp;display=swap">
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="box.css">
<style>
{css.strip()}
</style>
</helmet>
{body.strip()}
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{json.dumps(props, ensure_ascii=False)}'>
{logic or FS}
</script>
</body>
</html>
'''
    open(os.path.join(P, name + '.dc.html'), 'w', encoding='utf-8', newline='\n').write(html)
    BOARDS.append((name, title, w, h))


BOARDS = []


def state_logic(keys):
    """renderVals for boards whose `state` prop switches parts on: keys maps a value name to the states that show it."""
    lines = [f"      {k}: [{', '.join(repr(s) for s in v)}].includes(st) ? '{d}' : 'none'," for k, (v, d) in keys.items()]
    return ("class Component extends DCLogic {\n  renderVals() {\n    const st = this.props.state ?? 'normal';\n    return {\n"
            "      fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '',\n" + "\n".join(lines) + "\n    };\n  }\n}")


# ------------------------------------------------------------------ 공통: 카드 칸 여섯 종류의 제목·설명 (screens.md 7장)
KINDS = [
    ('알레르기', '약이나 음식 때문에 생기는 반응과 그 원인. 예: 페니실린 계열, 두드러기'),
    ('질환', '앓고 있거나 앓았던 병. 예: 고혈압, 당뇨병'),
    ('수술·시술', '받은 수술이나 시술과 그때. 예: 쓸개 수술, 2019년'),
    ('이식형 의료기기', '몸 안에 넣은 의료기기. 예: 심장 박동기, 인공 관절, 스텐트'),
    ('의료기관·담당 의사', '다니는 병원과 진료과, 담당 의사'),
    ('보호자 연락처', '응급 때 병원이 연락할 사람'),
]
ENTRIES = {
    '알레르기': [('페니실린 계열', '반응 두드러기')],
    '질환': [('고혈압', '2015년'), ('골다공증', '2023년 3월')],
    '수술·시술': [('쓸개 수술', '2019년')],
    '이식형 의료기기': [],
    '의료기관·담당 의사': [('가상내과의원', '내과 · 담당 의사 이가상 · 02-000-0000')],
    '보호자 연락처': [('홍가상', '딸 · 010-0000-0000')],
}
MEDS = [('가상정 10mg 1정', '매일 아침'), ('보기정 500mg 반 알', '매일 아침'), ('가상정 10mg 1정', '매일 저녁'), ('비타민', '매일')]


def kind_block(name, ex, entries, with_del=True):
    rows = ''.join(
        f'<div class="en"><span class="k">{a}<small>{b}</small></span>'
        + ('<button class="del" type="button">지우기</button>' if with_del else '') + '</div>' for a, b in entries)
    return (f'<section class="kind"><div class="kh"><h3>{name}</h3><button class="add" type="button">@PLUS@더하기</button></div>'
            f'<p class="ex">{ex}</p>{rows}</section>\n')


CARD_CSS = '''
.bar h1 .tag{display:inline-block;vertical-align:middle;margin-left:var(--space-xs);padding:2px var(--space-sm);border-radius:var(--radius-cell);
  background:var(--color-surface-skip);color:var(--color-ink-muted);font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold)}
.arch{margin-bottom:var(--space-md);padding:0 var(--space-2xs)}
.band .bb{display:flex;gap:var(--space-sm);flex-wrap:wrap;margin-top:var(--space-sm)}
.band .bb .btn{margin-top:0}
.acts{display:flex;flex-direction:column;gap:var(--space-sm);margin-top:var(--space-xs)}
.acts .two{display:grid;grid-template-columns:1fr 1fr;gap:var(--space-sm)}
.acts .note{padding:0 var(--space-2xs)}
.med{display:flex;align-items:center;gap:var(--space-sm);min-height:var(--size-target);padding:var(--space-xs) var(--space-2xs);border-bottom:var(--border-thin) solid var(--color-line-subtle)}
.med .k{flex-grow:1;line-height:var(--font-line-height-tight)}
.med .k b{font-weight:var(--font-weight-semibold)}
.med .k span{color:var(--color-ink-muted);white-space:nowrap}
.med .muted{display:var(--chev,flex)}
.medn{margin-top:var(--space-xs);padding:0 var(--space-2xs)}
.kind{margin-top:var(--space-xl);border-top:var(--border-thin) solid var(--color-line-subtle);padding-top:var(--space-md)}
.kh{display:flex;align-items:center;gap:var(--space-sm);padding:0 var(--space-2xs)}
.kh h3{margin:0;flex-grow:1;font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight)}
.add{min-height:var(--size-target);padding:0 var(--space-md);border-radius:var(--radius-button);border:var(--border-strong) dashed var(--color-line-strong);
  background:var(--color-surface-default);font:inherit;font-size:var(--font-size-label);font-weight:var(--font-weight-semibold);color:inherit;display:flex;align-items:center;gap:var(--space-2xs);flex-shrink:0}
.ex{margin-top:var(--space-2xs)!important;padding:0 var(--space-2xs);font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal);color:var(--color-ink-muted)}
.en{display:flex;align-items:center;gap:var(--space-sm);min-height:var(--size-input);padding:var(--space-xs) var(--space-2xs);border-bottom:var(--border-thin) solid var(--color-line-subtle)}
.en:last-child{border-bottom:0}
.en .k{flex-grow:1;line-height:var(--font-line-height-tight);font-weight:var(--font-weight-semibold)}
.en .k small{display:block;font-weight:var(--font-weight-regular);font-size:var(--font-size-body-sm);color:var(--color-ink-muted);margin-top:2px;line-height:var(--font-line-height-normal)}
.del{min-height:var(--size-target);padding:0 var(--space-md);border:0;background:none;font:inherit;font-size:var(--font-size-label);font-weight:var(--font-weight-semibold);color:var(--color-ink-danger);flex-shrink:0}
.last{padding:var(--space-xl) var(--space-2xs) 0}
/* 글자 200%: 버튼 셋은 세로로, 칸 종류의 [+ 더하기]와 칸 줄의 [지우기]는 줄 아래로 */
.fs200 .acts .two{grid-template-columns:1fr}
.fs200 .kh{flex-wrap:wrap}
.fs200 .kh h3{flex-basis:100%}
.fs200 .kh .add{margin-top:var(--space-xs)}
.fs200 .en{flex-wrap:wrap}
.fs200 .en .k{flex-basis:100%}
.fs200 .en .del{padding-left:0}
.fs200 .med{flex-wrap:wrap}
'''


def card_body():
    meds = ''.join(f'<div class="med p2"><span class="k"><b>{a}</b> <span>· {b}</span></span><span class="muted" style="display: {{{{chev}}}}">@CHEV@</span></div>' for a, b in MEDS)
    kinds = ''.join(kind_block(n, ex, ENTRIES[n]) for n, ex in KINDS)
    return ('''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>엄마의 건강 카드<span class="tag" style="display: {{arch}}">보관됨</span></h1></div>
  <div class="body">
    <p class="arch note" style="display: {{arch}}">보관한 대상자예요. 카드는 그대로 쓸 수 있어요.</p>
    <div class="band" style="display: {{recopy}}; margin-bottom: var(--space-md)">카드가 바뀌었어요. 휴대폰 긴급 정보에 넣은 문장도 다시 넣어 주세요.
      <div class="bb"><button class="btn ink sm" type="button">응급 대비 문장</button><button class="btn line sm" type="button">닫기</button></div></div>
    <div class="acts">
      <button class="btn ink" type="button">보여 주기</button>
      <div class="two"><button class="btn line" type="button">응급 대비 문장</button><button class="btn line" type="button">내보내기</button></div>
      <p class="note">응급 대비 문장: 휴대폰 긴급 정보에 붙여 넣을 짧은 글</p>
    </div>
    <section class="kind"><div class="kh"><h3>지금 먹는 약</h3></div></section>
''' + meds + '''
    <p class="medn note" style="display: {{medEdit}}">약은 복용 항목에서 고쳐요.</p>
    <p class="medn note" style="display: {{arch}}">약은 대상자를 되살린 뒤 복용 항목에서 고쳐요.</p>
''' + kinds + '''
    <p class="last note">마지막으로 고친 날 <span style="white-space:nowrap">2026-11-23</span></p>
    <button class="btn danger" type="button" style="margin-bottom: var(--space-xl)">카드 모두 지우기</button>
  </div>
</div>''')


CARD_STATES = {"state": {"editor": "enum", "options": ["normal", "recopy", "archived"], "default": "normal"}}
CARD_LOGIC = state_logic({'arch': (['archived'], 'block'), 'recopy': (['recopy'], 'block'),
                          'medEdit': (['normal', 'recopy'], 'block'), 'chev': (['normal', 'recopy'], 'flex')})
cb = card_body()
board('Card', 'SCR-011 건강 카드', cb, css=CARD_CSS, h=1610, props={**FSP, **CARD_STATES}, logic=CARD_LOGIC)
board('Card200', 'SCR-011 건강 카드 · 글자 200%', cb, css=CARD_CSS, h=3150,
      props={**FSP, "fontScale": {**FSP["fontScale"], "default": "200"}, **CARD_STATES}, logic=CARD_LOGIC)
board('CardRecopy', 'SCR-011 건강 카드 · 낡은 문장 안내', cb, css=CARD_CSS, h=1740,
      props={**FSP, **{"state": {**CARD_STATES["state"], "default": "recopy"}}}, logic=CARD_LOGIC)
board('CardArchived', 'SCR-011 건강 카드 · 보관한 대상자', cb, css=CARD_CSS, h=1630,
      props={**FSP, **{"state": {**CARD_STATES["state"], "default": "archived"}}}, logic=CARD_LOGIC)

board('CardEmpty', 'SCR-011 건강 카드 · 빈 카드', h=840, css=CARD_CSS + '''
.empty{padding:var(--space-xl) var(--space-lg);border-radius:var(--radius-tile);border:var(--border-strong) dashed var(--color-line-strong);text-align:center;line-height:var(--font-line-height-normal)}
.empty .et{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);margin-bottom:var(--space-xs)!important}
''', body='''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>나의 건강 카드</h1></div>
  <div class="body">
    <div class="empty"><p class="et">아직 적은 내용이 없어요.</p>
      <p class="muted sm">아래에서 칸을 적거나, 복용 항목에 약을 적으면 여기에 보여요.</p></div>
''' + ''.join(kind_block(n, ex, []) for n, ex in KINDS) + '''
  </div>
</div>''')

# ------------------------------------------------------------------ SCR-012 카드 칸 적기·고치기
ENTRY_CSS = '''
.ex{margin:0 var(--space-2xs) var(--space-lg)!important;font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal);color:var(--color-ink-muted)}
.unit{grid-template-columns:repeat(3,1fr)}
.ymd{display:flex;align-items:center;gap:var(--space-sm)}
.ymd .in{width:120px}
.cnt{align-self:flex-end;font-size:var(--font-size-body-sm);color:var(--color-ink-muted)}
.fs200 .unit{grid-template-columns:1fr}
.fs200 .ymd .in{width:160px}
'''
board('CardEntry', 'SCR-012 카드 칸 적기 · 수술·시술', h=800, css=ENTRY_CSS, body='''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>수술·시술 적기</h1></div>
  <div class="body">
    <p class="ex">받은 수술이나 시술과 그때. 예: 쓸개 수술, 2019년</p>
    <div class="fld"><label>이름</label><div class="in focus">쓸개 수술</div><span class="cnt">5/50</span></div>
    <div class="fld"><span class="lbl">시기 <span class="opt">(선택)</span></span>
      <div class="pick unit"><button class="c on" type="button" aria-pressed="true">연도만</button><button class="c" type="button">연·월</button><button class="c" type="button">연·월·일</button></div>
      <div class="ymd"><div class="in">2019</div><span>년</span></div>
      <p class="note">모르면 비워 두세요.</p></div>
    <div class="fld"><span class="lbl">받은 의료기관 <span class="opt">(선택)</span></span><div class="in ph">예: 가상대학교병원</div></div>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>''')
board('CardEntry200', 'SCR-012 카드 칸 적기 · 수술·시술 · 글자 200%', h=820, css=ENTRY_CSS,
      props={"fontScale": {**FSP["fontScale"], "default": "200"}}, body=open(os.path.join(P, 'CardEntry.dc.html'), encoding='utf-8').read()
      .split('</helmet>')[1].split('</x-dc>')[0])
board('CardEntryError', 'SCR-012 카드 칸 적기 · 보호자 연락처 · 전화번호 없이 저장', h=800, css=ENTRY_CSS, body='''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>보호자 연락처 적기</h1></div>
  <div class="body">
    <p class="ex">응급 때 병원이 연락할 사람</p>
    <div class="fld"><label>이름</label><div class="in">홍가상</div></div>
    <div class="fld"><span class="lbl">관계 <span class="opt">(선택)</span></span><div class="in">딸</div></div>
    <div class="fld"><label>전화번호</label><div class="in err"></div>
      <p class="err-msg">전화번호를 입력해 주세요</p></div>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>''')

# ------------------------------------------------------------------ SCR-013 보여 주기
SHOW = [('지금 먹는 약', [f'{a} · {b}' for a, b in MEDS]),
        ('알레르기', ['페니실린 계열 (반응: 두드러기)']),
        ('질환', ['고혈압 (2015년)', '골다공증 (2023년 3월)']),
        ('수술·시술', ['쓸개 수술 (2019년)']),
        ('의료기관·담당 의사', ['가상내과의원 (내과)<span class="sub">담당 의사 이가상 · 02-000-0000</span>']),
        ('보호자 연락처', ['홍가상 (딸) 010-0000-0000'])]
SHOW_BODY = ('''
<div class="s {{fs}}">
  <div class="bar"><span class="sp"></span><button class="ib" type="button" aria-label="닫기">@CLOSE@</button></div>
  <div class="body">
    <p class="who">엄마</p>
''' + ''.join(f'<section class="sk"><h3>{t}</h3>' + ''.join(f'<p class="it">{i}</p>' for i in items) + '</section>\n' for t, items in SHOW) + '''
    <div class="fine"><p>마지막으로 고친 날 2026-11-23</p><p>사용자가 적은 내용이며 의료기관의 기록이 아니에요</p></div>
  </div>
</div>''')
SHOW_CSS = '''
.bar .sp{flex-grow:1}
.who{font-size:var(--font-size-display);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight);padding:0 var(--space-2xs) var(--space-sm)}
.sk{border-top:var(--border-strong) solid var(--color-ink-default);padding:var(--space-md) var(--space-2xs) var(--space-lg)}
.sk h3{margin:0 0 var(--space-xs);font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);color:var(--color-ink-muted);line-height:var(--font-line-height-tight)}
.it{font-size:var(--font-size-title-md);font-weight:var(--font-weight-semibold);line-height:var(--font-line-height-normal)}
.it+.it{margin-top:var(--space-xs)!important}
.it .sub{display:block;font-size:var(--font-size-title-sm);font-weight:var(--font-weight-regular)}
.fine{border-top:var(--border-thin) solid var(--color-line-strong);padding:var(--space-md) var(--space-2xs) var(--space-xl);font-size:var(--font-size-body);line-height:var(--font-line-height-normal);color:var(--color-ink-muted)}
'''
board('CardShow', 'SCR-013 보여 주기', SHOW_BODY, css=SHOW_CSS, h=880)
board('CardShow200', 'SCR-013 보여 주기 · 글자 200%', SHOW_BODY, css=SHOW_CSS, h=1650,
      props={"fontScale": {**FSP["fontScale"], "default": "200"}})

# ------------------------------------------------------------------ SCR-014 응급 대비 문장
SENT = ('엄마. 알레르기: 페니실린 계열(두드러기). 지금 먹는 약: 가상정 10mg 1정 매일 아침, 보기정 500mg 반 알 매일 아침, '
        '가상정 10mg 1정 매일 저녁, 비타민 매일. 질환: 고혈압, 골다공증. 보호자: 홍가상(딸) 010-0000-0000. '
        '의료기관: 가상내과의원 내과 02-000-0000. 필받음 건강 카드, 2026-11-23 고침.')
EM_LOGIC = ("class Component extends DCLogic {\n  renderVals() {\n    const c = (this.props.state ?? 'normal') === 'copied';\n"
            "    return { fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '', copied: c ? 'block' : 'none' };\n  }\n}")
EM_PROPS = {**FSP, "state": {"editor": "enum", "options": ["normal", "copied"], "default": "normal"}}
EM_BODY = '''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>응급 대비 문장</h1></div>
  <div class="body">
    <p class="ex">휴대폰 긴급 정보에 붙여 넣을 짧은 글이에요.</p>
    <div class="txt">''' + re.sub(r'(\d[\d-]*\d)', r'<span style="white-space:nowrap">\1</span>', SENT) + '''</div>
    <p class="cnt">''' + f'{len(SENT)}자' + '''</p>
    <button class="btn ink" type="button">복사</button>
    <p class="ok" style="display: {{copied}}">이제 긴급 정보에 붙여 넣어 주세요.</p>
    <h2>넣는 곳</h2>
    <p class="sm" style="padding:0 var(--space-2xs)">휴대폰 설정의 긴급 정보에서 의료 정보에 붙여 넣어 주세요. 119안심콜에 등록할 때도 쓸 수 있어요.</p>
    <p class="note" style="padding:var(--space-xs) var(--space-2xs) var(--space-xl)">앱은 대신 등록하지 않아요.</p>
  </div>
</div>'''
EM_CSS = '''
.ex{margin:0 var(--space-2xs) var(--space-md)!important;font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal);color:var(--color-ink-muted)}
.txt{background:var(--color-surface-sunken);border-radius:var(--radius-tile);padding:var(--space-lg);line-height:var(--font-line-height-normal);font-size:var(--font-size-body)}
.cnt{text-align:right;padding:var(--space-xs) var(--space-2xs) var(--space-md);font-size:var(--font-size-body-sm);color:var(--color-ink-muted)}
.ok{margin-top:var(--space-sm)!important;padding:0 var(--space-2xs);font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold);line-height:var(--font-line-height-normal)}
'''
board('Emergency', 'SCR-014 응급 대비 문장', EM_BODY, css=EM_CSS, h=800, props=EM_PROPS, logic=EM_LOGIC)
board('EmergencyCopied', 'SCR-014 응급 대비 문장 · 복사한 뒤 (Android 13 이상)', EM_BODY, css=EM_CSS, h=800,
      props={**EM_PROPS, "state": {**EM_PROPS["state"], "default": "copied"}}, logic=EM_LOGIC)

# ------------------------------------------------------------------ SCR-015 내보내기
# SPEC-052: 한 쪽을 넘치면 쪽마다 대상자 이름·만든 날짜·쪽 번호. 2쪽 예(`pages`)는 보호자 연락처가 2쪽으로 넘어간 모양
# (PR #57 리뷰에서 다시 그림, 2026-11-23). 한 쪽(`normal`)은 모든 종류와 두 날짜·문구가 한 쪽에 듦.
def mini(rows):
    return ''.join(f'<b>{t}</b>' + ''.join(f'<i>{i}</i>' for i in items) for t, items in rows)


FINE = '마지막으로 고친 날 2026-11-23 · 만든 날 2026-11-23<br>사용자가 적은 내용이며 의료기관의 기록이 아니에요'
PAPER = ('<div class="paper"><p class="pn">엄마<span style="display: {{pagesIn}}"> · 1/2쪽</span></p>' + mini(SHOW[:-1]) +
         '<div style="display: {{one}}">' + mini(SHOW[-1:]) + '</div>'
         '<p class="pf" style="display: {{one}}">' + FINE + '</p>'
         '<p class="pf" style="display: {{pages}}">만든 날 2026-11-23</p></div>'
         '<div class="paper p2nd" style="display: {{pages}}"><p class="pn">엄마 · 2/2쪽</p>' + mini(SHOW[-1:]) +
         '<p class="pf">' + FINE + '</p></div>')
EX_LOGIC = ("class Component extends DCLogic {\n  renderVals() {\n    const p = (this.props.state ?? 'normal') === 'pages';\n"
            "    return { fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '', pages: p ? 'block' : 'none', "
            "pagesIn: p ? 'inline' : 'none', one: p ? 'none' : 'block' };\n  }\n}")
EX_PROPS = {**FSP, "state": {"editor": "enum", "options": ["normal", "pages"], "default": "normal"}}
EX_BODY = ('''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>내보내기</h1></div>
  <div class="body">
    <div class="pick" style="grid-template-columns:1fr 1fr"><button class="c on" type="button" aria-pressed="true">PDF</button><button class="c" type="button">사진(PNG)</button></div>
    <div class="band" style="display: {{pages}}; margin-top: var(--space-md)">카드가 길어 2쪽이에요. 쪽마다 이름과 쪽 번호가 들어가요.</div>
    <div class="pv">''' + PAPER + '''</div>
    <p class="note" style="padding:0 var(--space-2xs)">파일 이름: pillbadeum-card-20261123.pdf (대상자 이름은 넣지 않아요)</p>
  </div>
  <div class="foot line"><p class="note" style="text-align:center">앱이 직접 보내지 않아요. 고른 앱이 보내요.</p><button class="btn ink" type="button">보내기</button></div>
</div>''')
EX_CSS = '''
.pv{display:flex;flex-direction:column;align-items:center;gap:var(--space-md);padding:var(--space-lg) 0;margin:var(--space-md) 0;background:var(--color-surface-sunken);border-radius:var(--radius-tile)}
.paper{width:220px;aspect-ratio:210/297;background:var(--color-surface-default);border:var(--border-thin) solid var(--color-line-strong);padding:14px 14px;
  font-size:7px;line-height:1.4;color:var(--color-ink-default);overflow:hidden;font-family:Roboto,'Noto Sans KR',var(--font-family-base)}
.paper .pn{font-size:12px;font-weight:var(--font-weight-extrabold);margin-bottom:4px!important}
.paper b{display:block;margin-top:5px;font-size:7px;font-weight:var(--font-weight-extrabold);color:var(--color-ink-muted);border-top:var(--border-thin) solid var(--color-ink-default);padding-top:3px}
.paper i{display:block;font-style:normal;font-size:8px;font-weight:var(--font-weight-semibold)}
.paper i .sub{display:block;font-weight:var(--font-weight-regular)}
.paper .pf{margin-top:8px!important;font-size:6px;color:var(--color-ink-muted)}
'''
board('Export', 'SCR-015 내보내기', EX_BODY, css=EX_CSS, h=800, props=EX_PROPS, logic=EX_LOGIC)
board('ExportPages', 'SCR-015 내보내기 · 2쪽', EX_BODY, css=EX_CSS, h=1030,
      props={**EX_PROPS, "state": {**EX_PROPS["state"], "default": "pages"}}, logic=EX_LOGIC)

# ------------------------------------------------------------------ SCR-004: 헤더 [건강 카드]와 대상자 고르기 (BoxHome에 더함)
box = open(os.path.join(P, 'BoxHome.dc.html'), encoding='utf-8').read()
box_css = box.split('<style>')[1].split('</style>')[0]
box_body = box.split('</helmet>')[1].split('</x-dc>')[0]
old_bar = '''  <div class="bar">
    <h1>기록</h1>
    <button class="tb" type="button">지난 기록</button>
    <button class="tb" type="button">설정</button>
  </div>'''
assert box_body.count(old_bar) == 1
home_body = box_body.replace(old_bar, '''  <div class="bar">
    <h1>기록</h1>
    <button class="tb card" type="button">건강 카드</button>
    <button class="tb" type="button">지난 기록</button>
    <button class="tb" type="button">설정</button>
  </div>''')
anchor = '\n    <h2>오늘</h2>'
assert home_body.count(anchor) == 1
home_body = home_body.replace(anchor, '\n    <button class="btn line cardrow" type="button">@CARD@건강 카드</button>' + anchor)
HOME_CSS = box_css + '''
/* 2차: 헤더 버튼 셋. 글자 200%에서 한 줄에 들어가지 않으면 [건강 카드]를 날짜 아래 줄 버튼으로 내림 */
.bar .tb{padding:0 var(--space-sm)}
.cardrow{display:none;margin-top:var(--space-md)}
.fs200 .bar .tb.card{display:none}
.fs200 .cardrow{display:flex}
'''
SHEET_CSS = '''
.scrim{position:absolute;inset:0;background:color-mix(in srgb,var(--color-ink-default) 45%,transparent)}
.sheet{position:absolute;left:0;right:0;bottom:0;background:var(--color-surface-default);border-radius:var(--radius-widget) var(--radius-widget) 0 0;padding:var(--space-xl) var(--space-lg) var(--space-lg)}
.sheet .st{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight);padding:0 var(--space-2xs) var(--space-md)}
.sheet .btn{margin-top:var(--space-sm)}
'''
board('HomeCard', 'SCR-004 기록 · 헤더에 건강 카드', home_body, css=HOME_CSS)
board('HomeCard200', 'SCR-004 기록 · 헤더에 건강 카드 · 글자 200%', home_body, css=HOME_CSS, h=1580,
      props={"fontScale": {**FSP["fontScale"], "default": "200"}})
pick_sheet = '''
  <div class="scrim" aria-hidden="true"></div>
  <div class="sheet" role="dialog" aria-label="누구의 건강 카드를 볼까요?">
    <p class="st">누구의 건강 카드를 볼까요?</p>
    <div class="li p1"><span class="lid" aria-hidden="true">나</span><span class="k">나의 건강 카드</span><span class="muted">@CHEV@</span></div>
    <div class="li p2"><span class="lid" aria-hidden="true">엄</span><span class="k">엄마의 건강 카드</span><span class="muted">@CHEV@</span></div>
    <button class="btn text" type="button">닫기</button>
  </div>
</div>'''
pick_body = home_body.rstrip()
assert pick_body.endswith('</div>')
pick_body = pick_body[:-len('</div>')] + pick_sheet
board('HomeCardPick', 'SCR-004 기록 · 건강 카드 대상자 고르기', pick_body, css=HOME_CSS + SHEET_CSS)

# ------------------------------------------------------------------ SCR-006: 항목 안의 약 (1차 ItemForm에 '약' 부분을 더함)
MEDS_LOGIC = ("class Component extends DCLogic {\n  renderVals() {\n    const err = (this.props.state ?? 'normal') === 'error';\n"
              "    return { fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '', m2Cls: err ? 'in err' : 'in', m2Text: err ? '' : '보기정 500mg', errShow: err ? 'flex' : 'none' };\n  }\n}")
MEDS_PROPS = {**FSP, "state": {"editor": "enum", "options": ["normal", "error"], "default": "normal"}}
MEDS_BODY = '''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>항목 수정</h1></div>
  <div class="body">
    <div class="fld"><label>항목 이름</label><div class="in">아침약</div></div>
    <div class="fld"><span class="lbl">위젯·알림에 보일 이름 <span class="opt">(선택)</span></span><div class="in ph">예: 아침 약</div>
      <p class="note">홈 화면에서 항목 이름을 가리고 싶을 때 써요.</p></div>
    <div class="fld"><span class="lbl">누구 약인가요?</span>
      <div class="pick" style="grid-template-columns:1fr 1fr 1fr">
        <button class="c p p1" type="button">나</button><button class="c p p2 on" type="button" aria-pressed="true">엄마</button>
        <button class="c add" type="button">@PLUS@가족</button></div></div>
    <div class="fld"><span class="lbl">얼마나 자주?</span>
      <div class="pick" style="grid-template-columns:1fr 1fr"><button class="c on" type="button" aria-pressed="true">매일</button><button class="c" type="button">주 1회</button></div></div>
    <div class="fld"><span class="lbl">언제 먹나요?</span>
      <div class="pick five">
        <button class="c on" type="button" aria-pressed="true">아침</button><button class="c" type="button">점심</button><button class="c" type="button">저녁</button><button class="c" type="button">자기 전</button><button class="c" type="button">없음</button></div></div>
    <div class="fld meds"><span class="lbl">약 <span class="opt">(선택)</span></span>
      <p class="note">이 항목으로 먹는 약을 적으면 건강 카드에 보여요. 적지 않아도 돼요.</p>
      <div class="ml"><div class="mf"><span class="ml-l">약 이름</span><div class="in">가상정 10mg</div></div>
        <div class="mf q"><span class="ml-l">1회 투약량</span><div class="in">1정</div></div>
        <button class="rm" type="button">빼기</button></div>
      <div class="ml"><div class="mf"><span class="ml-l">약 이름</span><div class="{{m2Cls}}">{{m2Text}}</div>
          <p class="err-msg" style="display: {{errShow}}">약 이름을 입력해 주세요</p></div>
        <div class="mf q"><span class="ml-l">1회 투약량</span><div class="in">반 알</div></div>
        <button class="rm" type="button">빼기</button></div>
      <p class="note">1회 투약량은 한 번에 먹는 양이에요. 예: 1정, 반 알</p>
      <button class="btn line" type="button">@PLUS@약 더하기</button></div>
    <div class="li" style="border-bottom:0"><span class="k">알림 받기</span><span class="tg on" role="switch" aria-checked="true"></span></div>
    <div class="li"><span class="k">첫 알림 시각</span><button class="btn line sm" type="button">08:00</button></div>
    <p class="note" style="margin-top: var(--space-sm)">다시 알림 간격·하루 최대·방해 금지는 설정에서 바꿔요.</p>
    <button class="btn danger" type="button" style="margin-top: var(--space-lg)">보관</button>
    <p class="note" style="padding:0 var(--space-2xs) var(--space-lg)">보관해도 지난 기록은 지워지지 않아요.</p>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>'''
MEDS_CSS = '''
.pick .c.add{border-style:dashed;gap:var(--space-2xs)}
.pick.five{grid-template-columns:repeat(5,1fr)}
.pick.five .c{font-size:var(--font-size-label);padding:var(--space-2xs) 2px}
.fs200 .pick.five{grid-template-columns:repeat(3,1fr)}
.fld .note{margin-top:2px}
.meds{gap:var(--space-sm)}
.ml{display:grid;grid-template-columns:1fr 96px auto;gap:var(--space-xs);align-items:end;padding-bottom:var(--space-sm);border-bottom:var(--border-thin) solid var(--color-line-subtle)}
.mf{display:flex;flex-direction:column;gap:var(--space-3xs);min-width:0}
.ml-l{font-size:var(--font-size-body-sm);color:var(--color-ink-muted);line-height:var(--font-line-height-tight)}
.ml .in{padding:var(--space-sm) var(--space-md)}
.rm{min-height:var(--size-target);padding:0 var(--space-xs);border:0;background:none;font:inherit;font-size:var(--font-size-label);font-weight:var(--font-weight-semibold);color:var(--color-ink-danger);margin-bottom:4px}
/* 글자 200%: 약 이름과 1회 투약량을 세로로 쌓고 [빼기]는 아래 줄 */
.fs200 .ml{grid-template-columns:1fr}
.fs200 .rm{justify-self:start;padding-left:0}
'''
board('ItemFormMeds', 'SCR-006 항목 수정 · 약 두 줄', MEDS_BODY, css=MEDS_CSS, h=1260, props=MEDS_PROPS, logic=MEDS_LOGIC)
board('ItemFormMedsError', 'SCR-006 항목 수정 · 약 이름 없이 저장', MEDS_BODY, css=MEDS_CSS, h=1280,
      props={**MEDS_PROPS, "state": {**MEDS_PROPS["state"], "default": "error"}}, logic=MEDS_LOGIC)
board('ItemFormMeds200', 'SCR-006 항목 수정 · 약 두 줄 · 글자 200%', MEDS_BODY, css=MEDS_CSS, h=2120,
      props={**MEDS_PROPS, "fontScale": {**FSP["fontScale"], "default": "200"}}, logic=MEDS_LOGIC)

# ------------------------------------------------------------------ SCR-009: 의견 보내기 (설정 아래쪽으로 스크롤한 모양)
SET_BODY = '''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">@BACK@</button><h1>설정</h1></div>
  <div class="body">
    <h2>가족과 보관</h2>
    <div class="li"><span class="k">대상자 관리</span><span class="v">2명</span><span class="muted">@CHEV@</span></div>
    <div class="li"><span class="k">보관한 항목</span><span class="v">1개</span><span class="muted">@CHEV@</span></div>
    <h2>백업</h2>
    <p class="note" style="padding:0 var(--space-2xs)">화면 잠금과 Google 백업이 켜져 있으면 복용 기록이 백업돼요.</p>
    <h2>앱 정보</h2>
    <div class="li"><span class="k">버전</span><span class="v">2.0.0</span></div>
    <div class="li"><span class="k">개인정보처리방침</span><span class="muted">@OUT@</span></div>
    <div class="li"><span class="k">의견 보내기<small>메일 앱이 열려요. 앱 버전·Android 버전·기기 모델만 채워요.</small></span><span class="muted">@MAIL@</span></div>
    <p class="note" style="padding:var(--space-sm) var(--space-2xs)">의료기기가 아니며 질병을 진단·치료·예방하지&nbsp;않아요.</p>
  </div>
</div>'''
board('SettingsFeedback', 'SCR-009 설정 · 아래쪽 (의견 보내기)', SET_BODY, h=800)
nomail = SET_BODY.rstrip()[:-len('</div>')] + '''
  <div class="scrim" aria-hidden="true"></div>
  <div class="sheet" role="dialog" aria-label="메일 앱을 열 수 없어요">
    <p class="st">메일 앱을 열 수 없어요. 이 주소로 보내 주세요</p>
    <div class="in" style="margin-bottom: var(--space-sm); overflow-wrap: anywhere">feedback@example.com</div>
    <button class="btn ink" type="button">복사</button>
    <button class="btn text" type="button">닫기</button>
  </div>
</div>'''
board('SettingsNoMail', 'SCR-009 설정 · 메일 앱이 없을 때', nomail, css=SHEET_CSS)

# ------------------------------------------------------------------ SCR-010: 대상자 관리에서 카드 열기
PEOPLE_CSS = '''
.li .k .go{display:flex;align-items:center;gap:var(--space-3xs);color:var(--color-ink-default);font-weight:var(--font-weight-semibold);margin-top:var(--space-2xs)}
.fs200 .li.arch{flex-wrap:wrap}
.fs200 .li.arch .k{flex-basis:calc(100% - var(--size-lid) - var(--space-md))}
.fs200 .li.arch .btn{margin-left:calc(var(--size-lid) + var(--space-md))}
'''
PEOPLE_BODY = '''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">@BACK@</button><h1>대상자 관리</h1></div>
  <div class="body">
    <p class="note" style="padding:0 var(--space-2xs) var(--space-md)">사람마다 색이 있어요. 위젯과 앱에서 이 색으로 누구 약인지 알아봐요.</p>
    <div class="li p1"><span class="lid" aria-hidden="true">나</span><span class="k">나<small>약 2개</small></span><span class="muted">@CHEV@</span></div>
    <div class="li p2"><span class="lid" aria-hidden="true">엄</span><span class="k">엄마<small>약 2개</small></span><span class="muted">@CHEV@</span></div>
    <button class="btn ink" type="button" style="margin-top:var(--space-lg)">@PLUS@대상자 추가</button>
    <h2>보관됨</h2>
    <div class="li arch"><span class="lid off" aria-hidden="true">아</span><span class="k">아빠<small>약 1개 · 지난 기록은&nbsp;남아요</small><small class="go">건강 카드 보기 @CHEV@</small></span><button class="btn line sm" type="button">되살리기</button></div>
  </div>
</div>'''
board('PeopleCard', 'SCR-010 대상자 관리 · 보관한 대상자의 건강 카드', PEOPLE_BODY, css=PEOPLE_CSS)
board('PeopleCard200', 'SCR-010 대상자 관리 · 보관한 대상자의 건강 카드 · 글자 200%', PEOPLE_BODY, css=PEOPLE_CSS, h=800,
      props={"fontScale": {**FSP["fontScale"], "default": "200"}})
PRESSED = ' aria-pressed="true"'  # outside the f-string: Python before 3.12 allows no backslash inside {}
COLORS = ''.join(f'<button class="sw p{i}{" on" if i == 2 else ""}" type="button" aria-label="{n}"{PRESSED if i == 2 else ""}>{"@CHECK@" if i == 2 else ""}</button>'
                 for i, n in enumerate(['파랑', '주황', '초록', '보라', '장미', '청록'], 1))
board('PersonEditCard', 'SCR-010 대상자 수정 · 건강 카드', '''
<div class="s {{fs}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">@CLOSE@</button><h1>대상자 수정</h1></div>
  <div class="body">
    <button class="btn line" type="button" style="margin-bottom: var(--space-xl)">@CARD@엄마의 건강 카드</button>
    <div class="fld"><label>이름</label><div class="in">엄마</div></div>
    <div class="fld"><span class="lbl">색</span><div class="sws">''' + COLORS + '''</div>
      <p class="note">이미 쓰는 색: 파랑(나). 같은 색도 고를 수 있어요.</p></div>
    <button class="btn danger" type="button">보관</button>
    <p class="note" style="padding:0 var(--space-2xs)">보관해도 지난 기록은 지워지지 않아요.</p>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>''', css='''
.sws{display:grid;grid-template-columns:repeat(6,1fr);gap:var(--space-sm)}
.fs200 .sws{grid-template-columns:repeat(3,1fr)}
.sw{height:var(--size-target);border-radius:var(--radius-button);border:0;background:var(--p-solid);color:var(--color-ink-on-solid);display:flex;align-items:center;justify-content:center;padding:0}
.sw.on{box-shadow:0 0 0 2px var(--color-surface-default),0 0 0 4px var(--color-ink-default)}
''')

# canvas layout for the 2차 boards (rows: card / entry·show·emergency·export / changed 1차 screens)
rows = [['Card', 'Card200', 'CardRecopy', 'CardArchived', 'CardEmpty'],
        ['CardEntry', 'CardEntry200', 'CardEntryError', 'CardShow', 'CardShow200', 'Emergency', 'EmergencyCopied', 'Export', 'ExportPages'],
        ['HomeCard', 'HomeCard200', 'HomeCardPick', 'ItemFormMeds', 'ItemFormMedsError', 'ItemFormMeds200', 'SettingsFeedback', 'SettingsNoMail',
         'PeopleCard', 'PeopleCard200', 'PersonEditCard']]
meta = {n: (t, w, h) for n, t, w, h in BOARDS}
assert sorted(meta) == sorted(sum(rows, [])), sorted(set(meta) ^ set(sum(rows, [])))
layout, y = {}, 0
for row in rows:
    x = 0
    for n in row:
        t, w, h = meta[n]
        layout[n + '.dc.html'] = {"x": x, "y": y, "w": w, "h": h, "title": t}
        x += w + 80
    y += max(meta[n][2] for n in row) + 160
canvas = {"v": 3, "title": "필받음 2차 첫 릴리즈 목업 (건강 카드)", "launch": {"view": "canvas"}, "pages": [], "boards": layout,
          "order": [n + '.dc.html' for n in sum(rows, [])]}
open(os.path.join(P, 'canvas-p2.json'), 'w', encoding='utf-8', newline='\n').write(json.dumps(canvas, ensure_ascii=False, indent=2) + '\n')
print('written', len(BOARDS), 'boards')
