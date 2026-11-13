"""Writes the 2안 screen boards (*.dc.html) next to this file. Shared look: tokens.css + box.css.
BoxWidget, BoxWidget200 and BoxHome are written by hand. Run: python3 docs/09-mockup/boards/build_boards.py"""
import json, os
P = os.path.dirname(os.path.abspath(__file__))

def ic(d, size=24, sw=2.2):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" aria-hidden="true"><path d="{d}" fill="none" '
            f'stroke="currentColor" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/></svg>')
BACK = ic('M15 5l-7 7 7 7'); CLOSE = ic('M6 6l12 12M18 6L6 18'); CHEV = ic('M9 5l7 7-7 7', 20, 2)
PLUS = ic('M12 5v14M5 12h14', 18, 2.4); CHECK = ic('M5 12.5l4.5 4.5L19 7.5', 18, 2.6)
LEFT = ic('M15 5l-7 7 7 7', 24); RIGHT = ic('M9 5l7 7-7 7', 24)
OUT = ic('M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5', 18, 2)
DOTS = ('<svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true"><circle cx="4" cy="10" r="1.8" fill="currentColor"/>'
        '<circle cx="10" cy="10" r="1.8" fill="currentColor"/><circle cx="16" cy="10" r="1.8" fill="currentColor"/></svg>')

def board(name, title, body, css='', w=360, h=800, props=None, logic=None):
    props = props if props is not None else {"fontScale": {"editor": "enum", "options": ["100", "200"], "default": "100"}}
    props = {**props, "$preview": {"width": w, "height": h}}
    if h > 800 and '<div class="s ' in body:
        body = body.replace('<div class="s ', f'<div style="height: {h}px" class="s ', 1)
    logic = logic or ("class Component extends DCLogic {\n  renderVals() {\n"
                      "    return { fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '' };\n  }\n}")
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
{logic}
</script>
</body>
</html>
'''
    open(os.path.join(P, name + '.dc.html'), 'w', encoding='utf-8').write(html)

# ---------------------------------------------------------------- SCR-002 위젯 대상자 고르기
board('WidgetPick', 'SCR-002 위젯 대상자 고르기', f'''
<div class="s {{{{fs}}}}">
  <div class="body" style="padding-top: var(--space-2xl)">
    <p class="ttl">이 위젯에 누구 약을 보일까요?</p>
    <p class="muted sub">고른 사람의 약만 이 위젯에 보여요.</p>
    <div class="opts">
      <button class="opt" type="button"><span class="lid all" aria-hidden="true"><svg width="20" height="20" viewBox="0 0 20 20"><rect x="2" y="2" width="7" height="7" rx="2" fill="currentColor"/><rect x="11" y="2" width="7" height="7" rx="2" fill="currentColor"/><rect x="2" y="11" width="7" height="7" rx="2" fill="currentColor"/><rect x="11" y="11" width="7" height="7" rx="2" fill="currentColor"/></svg></span><span class="k"><b>전체</b><small>나·엄마의 약 4개</small></span></button>
      <button class="opt p1" type="button"><span class="lid" aria-hidden="true">나</span><span class="k"><b>나</b><small>약 2개</small></span></button>
      <button class="opt p2" type="button"><span class="lid" aria-hidden="true">엄</span><span class="k"><b>엄마</b><small>약 2개</small></span></button>
    </div>
  </div>
  <div class="foot"><button class="btn text" type="button">취소</button></div>
</div>''', css='''
.ttl{font-size:var(--font-size-title-lg);font-weight:var(--font-weight-extrabold);line-height:var(--font-line-height-tight);padding:0 var(--space-2xs)}
.sub{margin-top:var(--space-sm);padding:0 var(--space-2xs);line-height:var(--font-line-height-normal)}
.opts{display:flex;flex-direction:column;gap:var(--space-sm);margin-top:var(--space-2xl)}
.opt{display:flex;align-items:center;gap:var(--space-md);min-height:72px;padding:var(--space-md) var(--space-lg);border-radius:var(--radius-tile);
  border:var(--border-strong) solid var(--color-line-strong);background:var(--color-surface-default);font:inherit;color:inherit;text-align:left}
.opt .k{display:flex;flex-direction:column;gap:2px;line-height:var(--font-line-height-tight)}
.opt b{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold)}
.opt small{font-size:var(--font-size-body-sm);color:var(--color-ink-muted)}
''')

# ---------------------------------------------------------------- SCR-003 반복 알림 (시스템이 그림)
board('Notif', 'SCR-003 반복 알림', f'''
<div class="sys">
  <p class="cap">잠금을 푼 화면 · 알림을 펼친 모양</p>
  <div class="n">
    <div class="nh"><span class="ai" aria-hidden="true">{CHECK}</span><span>필받음 · 지금</span></div>
    <p class="nt">혈압약 먹어~!</p>
    <p class="nb">마지막 복용 어제 21:40</p>
    <div class="na"><button type="button">먹었어</button><button type="button">30분 뒤</button><button type="button">오늘 건너뜀</button></div>
  </div>
  <p class="cap">잠금 화면 · 약 이름 보이기 끔(기본)</p>
  <div class="n">
    <div class="nh"><span class="ai" aria-hidden="true">{CHECK}</span><span>필받음 · 지금</span></div>
    <p class="nt">복용 시간이에요</p>
  </div>
  <p class="cap">주 1회 약 · 펼친 모양</p>
  <div class="n">
    <div class="nh"><span class="ai" aria-hidden="true">{CHECK}</span><span>필받음 · 지금</span></div>
    <p class="nt">골다공증약 먹어~!</p>
    <p class="nb">마지막 복용 11/02(월) 08:10</p>
    <div class="na"><button type="button">먹었어</button><button type="button">30분 뒤</button><button type="button">이번 주 건너뜀</button></div>
  </div>
</div>''', w=360, h=560, props={}, logic='class Component extends DCLogic {}', css='''
/* 알림 모양은 기기(Android 14 밝은 테마)가 그림. 앱이 정하는 것은 글자, 작은 아이콘, 강조색(--color-ink-default)뿐 */
.sys{width:360px;height:560px;box-sizing:border-box;word-break:keep-all;padding:16px 12px;background:#DADDE3;font-family:Roboto,'Noto Sans KR',sans-serif;display:flex;flex-direction:column;gap:8px}
.sys p{margin:0}
.cap{font-size:13px;color:#3B3F46;padding:6px 8px 0}
.n{background:#F7F7FA;border-radius:24px;padding:14px 16px 6px;color:#1B1B1F}
.nh{display:flex;align-items:center;gap:8px;font-size:13px;color:#46464F}
.ai{width:22px;height:22px;border-radius:50%;background:var(--color-ink-default);color:#fff;display:flex;align-items:center;justify-content:center}
.ai svg{width:14px;height:14px}
.nt{font-size:16px;font-weight:600;margin-top:8px!important}
.nb{font-size:14px;color:#46464F;margin-top:2px!important;padding-bottom:8px}
.na{display:flex;gap:4px;margin:0 -8px}
.na button{min-height:48px;border:0;background:none;font:inherit;font-size:14px;font-weight:600;color:var(--color-ink-default);padding:0 8px}
''')

# ---------------------------------------------------------------- SCR-005 첫 실행 안내 1·2
WEEK = ''.join(f'<span class="d">{d}</span>' for d in '월화수목금토일')
LIDS = ('<i class="lidc t">' + CHECK + '</i>') * 3 + '<i class="lidc now"></i>' + '<i class="lidc"></i>' * 3
board('Welcome1', 'SCR-005 첫 실행 안내 1쪽', f'''
<div class="s p1 {{{{fs}}}}">
  <div class="bar"><span style="flex-grow:1"></span><button class="tb" type="button">건너뛰기</button></div>
  <div class="body">
    <div class="box" role="img" aria-label="요일 약통 그림: 월 화 수는 먹음, 목요일은 오늘">
      <div class="days">{WEEK}</div><div class="lids">{LIDS}</div>
    </div>
    <p class="hl">먹었는지 헷갈릴 때, 위젯 한 번이면&nbsp;돼요</p>
    <p class="lead">약을 먹고 홈 화면 위젯을 한 번 누르면 시각과 함께 남아요.</p>
    <div class="pts">
      <p><b>필받음은 기록 도구예요.</b> 복용 방법은 의사·약사와 상의해 주세요.</p>
      <p><b>한 약은 한 휴대폰에서 기록해 주세요.</b> 휴대폰끼리는 기록이 이어지지 않아요.</p>
    </div>
  </div>
  <div class="foot"><p class="pg" aria-label="2쪽 중 1쪽"><i class="on"></i><i></i></p><button class="btn ink" type="button">다음</button></div>
</div>''', css='''
.box{margin:var(--space-lg) 0 var(--space-2xl);padding:var(--space-lg) var(--space-md);border-radius:var(--radius-tile);background:var(--color-surface-sunken)}
.days,.lids{display:grid;grid-template-columns:repeat(7,1fr);gap:var(--space-xs)}
.days .d{text-align:center;font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold);color:var(--color-ink-muted);margin-bottom:var(--space-xs)}
.lidc{height:56px;border-radius:var(--radius-button);background:var(--color-surface-default);border:var(--border-strong) solid var(--color-line-strong);display:flex;align-items:center;justify-content:center;color:var(--color-ink-on-solid)}
.lidc.t{background:var(--p-solid);border-color:var(--p-solid)}
.lidc.now{border:var(--border-focus) dashed var(--p-solid);box-shadow:0 0 0 2px var(--color-surface-sunken),0 0 0 4px var(--color-ink-default)}
.hl{font-size:var(--font-size-display);font-weight:var(--font-weight-extrabold);line-height:1.3;padding:0 var(--space-2xs)}
.lead{margin-top:var(--space-md)!important;line-height:var(--font-line-height-normal);color:var(--color-ink-muted);padding:0 var(--space-2xs)}
.pts{margin-top:var(--space-xl);display:flex;flex-direction:column;gap:var(--space-md);padding:0 var(--space-2xs)}
.pts p{font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal);padding-left:var(--space-md);border-left:3px solid var(--color-ink-default)}
.pg{display:flex;gap:var(--space-xs);justify-content:center;margin-bottom:var(--space-2xs)!important}
.pg i{width:20px;height:4px;border-radius:2px;background:var(--color-line-strong)}
.pg i.on{width:32px;background:var(--color-ink-default)}
''')

MINI = '''<div class="mini" aria-hidden="true">
  <div class="mh"><span class="md">목</span><span>필받음 · 11/12</span><span class="mc"><b>2 / 4</b> 완료</span></div>
  <div class="mg">
    <span class="mt p1 done"><b>아침약</b><small>08:02 먹음</small></span>
    <span class="mt sk"><b>비타민D</b><small>건너뜀</small></span>
    <span class="mt p2"><b>혈압약</b><small>어제 21:40</small></span>
    <span class="mt p2"><b>골다공증약</b><small>11/02(월)</small></span>
  </div></div>'''
board('Welcome2', 'SCR-005 첫 실행 안내 2쪽', f'''
<div class="s {{{{fs}}}}">
  <div class="bar"><span style="flex-grow:1"></span><button class="tb" type="button">건너뛰기</button></div>
  <div class="body">
    <p class="hl">홈 화면에 위젯을 놓아 주세요</p>
    <p class="lead">위젯에서 바로 누르면 앱을 열지 않아도 기록돼요.</p>
    {MINI}
    <ol class="steps">
      <li>홈 화면의 빈 곳을 길게 누르기</li>
      <li>'위젯'에서 필받음 찾기</li>
      <li>4×2 크기로 끌어다 놓기</li>
    </ol>
    <p class="note" style="padding:var(--space-md) var(--space-2xs) 0">휴대폰마다 메뉴 이름이 조금 달라요.</p>
  </div>
  <div class="foot"><p class="pg" aria-label="2쪽 중 2쪽"><i></i><i class="on"></i></p><button class="btn ink" type="button">위젯 추가</button><button class="btn line" type="button">시작하기</button></div>
</div>''', css='''
.hl{font-size:var(--font-size-display);font-weight:var(--font-weight-extrabold);line-height:1.3;padding:var(--space-lg) var(--space-2xs) 0}
.lead{margin-top:var(--space-md)!important;line-height:var(--font-line-height-normal);color:var(--color-ink-muted);padding:0 var(--space-2xs)}
.mini{margin:var(--space-xl) 0;padding:10px 12px;border-radius:var(--radius-widget);background:var(--color-surface-default);box-shadow:0 0 0 1px var(--color-line-subtle),0 6px 18px rgba(21,23,28,.12);font-size:12px}
.mh{display:flex;align-items:center;gap:6px;font-weight:var(--font-weight-semibold)}
.md{width:22px;height:22px;border-radius:7px;background:var(--color-ink-default);color:var(--color-ink-on-solid);display:flex;align-items:center;justify-content:center;font-weight:var(--font-weight-extrabold)}
.mc{margin-left:auto;color:var(--color-ink-muted);font-weight:var(--font-weight-regular)}
.mc b{color:var(--color-ink-default)}
.mg{display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-top:7px}
.mt{border-radius:12px;padding:7px 10px;display:flex;flex-direction:column;gap:1px;background:var(--p-tint);color:var(--p-ink)}
.mt b{font-size:13px;font-weight:var(--font-weight-extrabold)}
.mt.done{background:var(--p-solid);color:var(--color-ink-on-solid)}
.mt.sk{background:var(--color-surface-skip);color:var(--color-ink-muted)}
.steps{margin:0;padding:0 0 0 var(--space-2xs);list-style:none;counter-reset:s;display:flex;flex-direction:column;gap:var(--space-sm)}
.steps li{counter-increment:s;display:flex;gap:var(--space-md);align-items:center;line-height:var(--font-line-height-tight)}
.steps li::before{content:counter(s);width:28px;height:28px;border-radius:var(--radius-cell);background:var(--color-ink-default);color:var(--color-ink-on-solid);font-weight:var(--font-weight-extrabold);font-size:var(--font-size-body-sm);display:flex;align-items:center;justify-content:center;flex-shrink:0}
.pg{display:flex;gap:var(--space-xs);justify-content:center;margin-bottom:var(--space-2xs)!important}
.pg i{width:20px;height:4px;border-radius:2px;background:var(--color-line-strong)}
.pg i.on{width:32px;background:var(--color-ink-default)}
''')

# ---------------------------------------------------------------- SCR-006 항목 추가 (보통·오류)
FORM_LOGIC = '''class Component extends DCLogic {
  renderVals() {
    const err = (this.props.state ?? 'normal') === 'error';
    return {
      fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '',
      nameCls: err ? 'in err' : 'in focus', nameText: err ? '' : '골다공증약',
      errShow: err ? 'flex' : 'none'
    };
  }
}'''
FORM_BODY = f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">{CLOSE}</button><h1>항목 추가</h1></div>
  <div class="body">
    <div class="fld"><label>약 이름</label><div class="{{{{nameCls}}}}">{{{{nameText}}}}</div>
      <p class="err-msg" style="display: {{{{errShow}}}}">이름을 입력해 주세요</p></div>
    <div class="fld"><span class="lbl">위젯·알림에 보일 이름 <span class="opt">(선택)</span></span><div class="in ph">예: 아침 약</div>
      <p class="note">홈 화면에서 약 이름을 가리고 싶을 때 써요.</p></div>
    <div class="fld"><span class="lbl">누구 약인가요?</span>
      <div class="pick" style="grid-template-columns:1fr 1fr 1fr">
        <button class="c p p1" type="button">나</button><button class="c p p2 on" type="button" aria-pressed="true">엄마</button>
        <button class="c add" type="button">{PLUS}가족</button></div></div>
    <div class="fld"><span class="lbl">얼마나 자주?</span>
      <div class="pick" style="grid-template-columns:1fr 1fr"><button class="c" type="button">매일</button><button class="c on" type="button" aria-pressed="true">주 1회</button></div></div>
    <div class="fld"><span class="lbl">무슨 요일에?</span>
      <div class="pick" style="grid-template-columns:repeat(4,1fr)">
        <button class="c on" type="button" aria-pressed="true">월</button><button class="c" type="button">화</button><button class="c" type="button">수</button><button class="c" type="button">목</button>
        <button class="c" type="button">금</button><button class="c" type="button">토</button><button class="c" type="button">일</button></div>
      <p class="note">월요일부터 일요일 사이에 한 번 먹으면 완료예요.</p></div>
    <div class="fld"><span class="lbl">언제 먹나요?</span>
      <div class="pick five">
        <button class="c on" type="button" aria-pressed="true">아침</button><button class="c" type="button">점심</button><button class="c" type="button">저녁</button><button class="c" type="button">자기 전</button><button class="c" type="button">없음</button></div></div>
    <div class="li" style="border-bottom:0"><span class="k">알림 받기<small>그 주의 월요일에만 울려요.</small></span><span class="tg on" role="switch" aria-checked="true"></span></div>
    <div class="li"><span class="k">첫 알림 시각</span><button class="btn line sm" type="button">08:00</button></div>
    <p class="note" style="margin-top: var(--space-sm)">다시 알림 간격·하루 최대·방해 금지는 설정에서 바꿔요.</p>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>'''
FORM_CSS = '''
.pick .c.add{border-style:dashed;gap:var(--space-2xs)}
.pick.five{grid-template-columns:repeat(5,1fr)}
.pick.five .c{font-size:var(--font-size-label);padding:var(--space-2xs) 2px}
.fs200 .pick.five{grid-template-columns:repeat(3,1fr)}
.fld .note{margin-top:2px}
'''
FPROPS = {"fontScale": {"editor": "enum", "options": ["100", "200"], "default": "100"}, "state": {"editor": "enum", "options": ["normal", "error"], "default": "normal"}}
board('ItemForm', 'SCR-006 항목 추가', FORM_BODY, css=FORM_CSS, h=1010, props=FPROPS, logic=FORM_LOGIC)
board('ItemFormError', 'SCR-006 항목 추가 · 이름 없이 저장', FORM_BODY, css=FORM_CSS, h=1040,
      props={**FPROPS, "state": {**FPROPS["state"], "default": "error"}}, logic=FORM_LOGIC)
board('ItemForm200', 'SCR-006 항목 추가 · 글자 200%', FORM_BODY, css=FORM_CSS, h=1380,
      props={**FPROPS, "fontScale": {**FPROPS["fontScale"], "default": "200"}}, logic=FORM_LOGIC)

# ---------------------------------------------------------------- SCR-007 판정 고치기 (기록 없음·먹음)
JUDGE_LOGIC = '''class Component extends DCLogic {
  renderVals() {
    const taken = (this.props.state ?? 'none') === 'taken';
    return {
      fs: (this.props.fontScale ?? '100') === '200' ? 'fs200' : '',
      who: taken ? 'p1' : 'p2', tileCls: taken ? 'done' : '', whoName: taken ? '나' : '엄마',
      item: taken ? '아침약' : '혈압약', cyc: taken ? '매일 · 아침' : '매일 · 저녁',
      status: taken ? '08:02에 먹음' : '아직 기록이 없어요',
      noneShow: taken ? 'none' : 'flex', takenShow: taken ? 'flex' : 'none'
    };
  }
}'''
board('Judge', 'SCR-007 판정 고치기 · 기록 없음', f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">{BACK}</button><h1>오늘 기록</h1></div>
  <div class="body {{{{who}}}}">
    <div class="tile {{{{tileCls}}}}">
      <span class="who">{{{{whoName}}}}</span><span class="nm">{{{{item}}}}</span><span class="sm">{{{{cyc}}}}</span>
      <span class="win">11/12 (목) 04:00 ~ 11/13 (금) 04:00</span>
      <span class="st">{{{{status}}}}</span>
    </div>
    <div class="acts" style="display: {{{{noneShow}}}}">
      <button class="btn p" type="button">지금 먹었어</button>
      <button class="btn line" type="button">먹은 시각 고르기</button>
      <button class="btn line" type="button">건너뜀</button>
      <p class="note">먹은 시각은 오늘 04:00부터 지금(18:30)까지 고를 수 있어요. 다른 날은 지난 기록에서 열어요.</p>
    </div>
    <div class="acts" style="display: {{{{takenShow}}}}">
      <button class="btn line" type="button">먹은 시각 고치기</button>
      <button class="btn danger" type="button">기록 취소</button>
      <p class="note">기록을 취소해도 지워지지 않고 무효로 남아요.</p>
    </div>
  </div>
</div>''', props={"fontScale": {"editor": "enum", "options": ["100", "200"], "default": "100"}, "state": {"editor": "enum", "options": ["none", "taken"], "default": "none"}},
      logic=JUDGE_LOGIC, css='''
.tile{border-radius:var(--radius-tile);padding:var(--space-lg);display:flex;flex-direction:column;gap:2px;background:var(--p-tint);color:var(--p-ink);margin-top:var(--space-sm)}
.tile.done{background:var(--p-solid);color:var(--color-ink-on-solid)}
.tile .who{font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold)}
.tile .nm{font-size:var(--font-size-display-sm);font-weight:var(--font-weight-extrabold);line-height:1.3}
.tile .win{margin-top:var(--space-md);font-size:var(--font-size-body-sm);line-height:var(--font-line-height-normal)}
.tile .st{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);margin-top:2px}
.acts{flex-direction:column;gap:var(--space-sm);margin-top:var(--space-xl)}
.acts .note{margin-top:var(--space-2xs);padding:0 var(--space-2xs)}
''')
txt = open(os.path.join(P, 'Judge.dc.html'), encoding='utf-8').read()
open(os.path.join(P, 'JudgeTaken.dc.html'), 'w', encoding='utf-8').write(
    txt.replace('<title>SCR-007 판정 고치기 · 기록 없음</title>', '<title>SCR-007 판정 고치기 · 먹음</title>')
       .replace('"options": ["none", "taken"], "default": "none"', '"options": ["none", "taken"], "default": "taken"'))

# ---------------------------------------------------------------- SCR-008 지난 기록
def comp(cls, name, val, label):
    return f'<button class="cp {cls}" type="button" aria-label="{label}"><b>{name}</b><span>{val}</span></button>'
DAYS = [
  ('12', '목', '오늘', [comp('p1 tk', '아침약', '08:02', '나 아침약 08:02 먹음'), comp('sk', '비타민D', '건너뜀', '나 비타민D 건너뜀'), comp('p2 td', '혈압약', '아직', '엄마 혈압약 아직')]),
  ('11', '수', '', [comp('p1 tk', '아침약', '08:10', '나 아침약 08:10 먹음'), comp('p1 tk', '비타민D', '08:11', '나 비타민D 08:11 먹음'), comp('ms', '혈압약', '빠짐', '엄마 혈압약 빠짐')]),
  ('10', '화', '', [comp('p1 tk', '아침약', '07:55', '나 아침약 07:55 먹음'), comp('p1 tk', '비타민D', '07:56', '나 비타민D 07:56 먹음'), comp('p2 tk', '혈압약', '21:02', '엄마 혈압약 21:02 먹음')]),
  ('9', '월', '', [comp('p1 tk', '아침약', '08:30', '나 아침약 08:30 먹음'), comp('p1 tk', '비타민D', '08:31', '나 비타민D 08:31 먹음'), comp('p2 tk', '혈압약', '21:40', '엄마 혈압약 21:40 먹음'),
                   comp('p2 td wk', '골다공증약', '11/09~11/15 아직', '엄마 골다공증약 이번 주 11월 9일부터 15일 아직')]),
  ('8', '일', '', [comp('p1 tk', '아침약', '09:12', '나 아침약 09:12 먹음'), comp('ms', '비타민D', '빠짐', '나 비타민D 빠짐'), comp('p2 tk', '혈압약', '22:05', '엄마 혈압약 22:05 먹음')]),
]
rows = ''.join(f'<div class="day"><div class="dt"><b>{d}</b><span>{w}</span>{f"<em>{t}</em>" if t else ""}</div><div class="cps">{"".join(c)}</div></div>' for d, w, t, c in DAYS)
board('History', 'SCR-008 지난 기록', h=880, body=f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">{BACK}</button><h1>지난 기록</h1></div>
  <div class="body">
    <div class="mon"><button class="ib" type="button" aria-label="10월">{LEFT}</button><b>2026년 11월</b><button class="ib off" type="button" aria-label="다음 달 없음" disabled>{RIGHT}</button></div>
    <div class="pick" style="grid-template-columns:1fr 1fr 1fr;margin-bottom:var(--space-md)">
      <button class="c on" type="button" aria-pressed="true">전체</button><button class="c" type="button">나</button><button class="c" type="button">엄마</button></div>
    {rows}
  </div>
</div>''', css='''
.mon{display:flex;align-items:center;justify-content:space-between;margin-bottom:var(--space-sm)}
.mon b{font-size:var(--font-size-title-md);font-weight:var(--font-weight-extrabold)}
.ib.off{color:var(--color-line-strong)}
.day{display:flex;gap:var(--space-md);padding:var(--space-md) 0;border-top:var(--border-thin) solid var(--color-line-subtle)}
.dt{width:44px;flex-shrink:0;display:flex;flex-direction:column;align-items:center;line-height:var(--font-line-height-tight)}
.dt b{font-size:var(--font-size-title-md);font-weight:var(--font-weight-extrabold)}
.dt span{font-size:var(--font-size-body-sm);color:var(--color-ink-muted)}
.dt em{font-style:normal;font-size:var(--font-size-body-sm);font-weight:var(--font-weight-extrabold);margin-top:2px}
.cps{flex-grow:1;display:grid;grid-template-columns:1fr 1fr;gap:var(--space-xs);min-width:0}
.cp{min-height:var(--size-target);border-radius:var(--radius-button);border:0;font:inherit;text-align:left;padding:var(--space-xs) var(--space-md);display:flex;flex-direction:column;justify-content:center;line-height:var(--font-line-height-tight);min-width:0}
.cp b{font-size:var(--font-size-body-sm);font-weight:var(--font-weight-extrabold)}
.cp span{font-size:var(--font-size-body-sm)}
.cp.tk{background:var(--p-solid);color:var(--color-ink-on-solid)}
.cp.td{background:var(--color-surface-default);border:var(--border-focus) dashed var(--p-solid);color:var(--p-ink)}
.cp.sk{background:var(--color-surface-skip);color:var(--color-ink-muted)}
.cp.ms{background:var(--color-surface-default);border:var(--border-strong) solid var(--color-line-strong);color:var(--color-ink-muted)}
.cp.wk{grid-column:1 / -1}
''')

# ---------------------------------------------------------------- SCR-009 설정
board('Settings', 'SCR-009 설정', h=1190, body=f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">{BACK}</button><h1>설정</h1></div>
  <div class="body">
    <h2>하루 기준</h2>
    <div class="li"><span class="k">하루 시작 시각</span><span class="v">04:00</span></div>
    <div class="li"><span class="k">기준 시간대</span><span class="v">Asia/Seoul</span></div>
    <h2>알림 (모든 약 같이)</h2>
    <div class="li"><span class="k">다시 알림 간격</span><span class="v">60분</span></div>
    <div class="li"><span class="k">하루 최대</span><span class="v">3번</span></div>
    <div class="li"><span class="k">방해 금지</span><span class="v">22:00 ~ 07:00</span></div>
    <div class="li"><span class="k">잠금 화면에 약 이름 보이기<small>끄면 '복용 시간이에요'만 보여요.</small></span><span class="tg" role="switch" aria-checked="false"></span></div>
    <h2>알림이 오지 않을 때</h2>
    <div class="li"><span class="k">알림 권한</span><span class="v ok">켜짐</span></div>
    <div class="li"><span class="k">정확한 시각 알람<small>꺼져 있으면 알림이 늦을 수 있어요.</small></span><button class="btn line sm" type="button">허용하기</button></div>
    <div class="li"><span class="k">배터리 사용 제한</span><button class="btn line sm" type="button">설정 열기</button></div>
    <h2>가족과 보관</h2>
    <div class="li"><span class="k">대상자 관리</span><span class="v">2명</span><span class="muted">{CHEV}</span></div>
    <div class="li"><span class="k">보관한 항목</span><span class="v">1개</span><span class="muted">{CHEV}</span></div>
    <h2>백업</h2>
    <p class="note" style="padding:0 var(--space-2xs)">화면 잠금과 Google 백업이 켜져 있으면 복용 기록이 백업돼요.</p>
    <h2>앱 정보</h2>
    <div class="li"><span class="k">버전</span><span class="v">1.0.0</span></div>
    <div class="li"><span class="k">개인정보처리방침</span><span class="muted">{OUT}</span></div>
    <p class="note" style="padding:var(--space-sm) var(--space-2xs)">의료기기가 아니며 질병을 진단·치료·예방하지&nbsp;않아요.</p>
  </div>
</div>''')

# ---------------------------------------------------------------- SCR-010 대상자 관리 (목록·추가)
board('People', 'SCR-010 대상자 관리', f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="뒤로">{BACK}</button><h1>대상자 관리</h1></div>
  <div class="body">
    <p class="note" style="padding:0 var(--space-2xs) var(--space-md)">사람마다 색이 있어요. 위젯과 앱에서 이 색으로 누구 약인지 알아봐요.</p>
    <div class="li p1"><span class="lid" aria-hidden="true">나</span><span class="k">나<small>약 2개</small></span><span class="muted">{CHEV}</span></div>
    <div class="li p2"><span class="lid" aria-hidden="true">엄</span><span class="k">엄마<small>약 2개</small></span><span class="muted">{CHEV}</span></div>
    <button class="btn ink" type="button" style="margin-top:var(--space-lg)">{PLUS}대상자 추가</button>
    <h2>보관됨</h2>
    <div class="li arch"><span class="lid off" aria-hidden="true">아</span><span class="k">아빠<small>약 1개 · 지난 기록은&nbsp;남아요</small></span><button class="btn line sm" type="button">되살리기</button></div>
  </div>
</div>''', css='''
.fs200 .li.arch{flex-wrap:wrap}
.fs200 .li.arch .k{flex-basis:calc(100% - var(--size-lid) - var(--space-md))}
.fs200 .li.arch .btn{margin-left:calc(var(--size-lid) + var(--space-md))}
''')

COLORS = ''.join(f'<button class="sw p{i}{" on" if i == 3 else ""}" type="button" aria-label="{n}"{" aria-pressed=\"true\"" if i == 3 else ""}>{CHECK if i == 3 else ""}</button>'
                 for i, n in enumerate(['파랑', '주황', '초록', '보라', '장미', '청록'], 1))
board('PersonAdd', 'SCR-010 대상자 추가', f'''
<div class="s {{{{fs}}}}">
  <div class="bar back"><button class="ib" type="button" aria-label="닫기">{CLOSE}</button><h1>대상자 추가</h1></div>
  <div class="body">
    <div class="fld"><label>이름</label><div class="in focus">아빠</div></div>
    <div class="fld"><span class="lbl">색</span><div class="sws">{COLORS}</div>
      <p class="note">파랑·주황은 나·엄마가 쓰고 있어요. 같은 색도 고를 수 있어요.</p></div>
    <div class="band">한 약은 한 휴대폰에서 기록해 주세요. 아빠 약을 아빠 휴대폰에서도 기록하면 두 기록이 서로 보이지 않아요.</div>
  </div>
  <div class="foot line"><button class="btn ink" type="button">저장</button></div>
</div>''', css='''
.sws{display:grid;grid-template-columns:repeat(6,1fr);gap:var(--space-sm)}
.sw{height:var(--size-target);border-radius:var(--radius-button);border:0;background:var(--p-solid);color:var(--color-ink-on-solid);display:flex;align-items:center;justify-content:center;padding:0}
.sw.on{box-shadow:0 0 0 2px var(--color-surface-default),0 0 0 4px var(--color-ink-default)}
''')

# ---------------------------------------------------------------- SCR-004 상태: 비어 있음, 복원 뒤
board('HomeEmpty', 'SCR-004 기록 · 항목 없음', f'''
<div class="s {{{{fs}}}}">
  <div class="bar"><h1>기록</h1><button class="tb" type="button">지난 기록</button><button class="tb" type="button">설정</button></div>
  <div class="body">
    <div class="hero"><span class="dow" aria-hidden="true">목</span><div><p class="d1">11월 12일 목요일</p><p class="note">하루 시작 04:00</p></div></div>
    <div class="empty">
      <div class="lids" aria-hidden="true">{'<i></i>' * 7}</div>
      <p class="et">아직 복용 항목이 없어요.</p>
      <p class="muted">[항목 추가]로 등록해 주세요.</p>
      <button class="btn ink" type="button" style="margin-top:var(--space-xl)">{PLUS}항목 추가</button>
    </div>
    <p class="note" style="margin-top:var(--space-2xl);padding:0 var(--space-2xs)">기록 도구이며 복용 방법은 의사·약사와 상의해&nbsp;주세요.</p>
  </div>
</div>''', css='''
.hero{display:flex;align-items:center;gap:var(--space-md);padding:0 var(--space-2xs)}
.d1{font-size:var(--font-size-title-md);font-weight:var(--font-weight-extrabold);line-height:1.3}
.empty{margin-top:var(--space-2xl);padding:var(--space-2xl) var(--space-lg);border-radius:var(--radius-tile);border:var(--border-strong) dashed var(--color-line-strong);text-align:center}
.empty .lids{display:grid;grid-template-columns:repeat(7,1fr);gap:var(--space-xs);margin-bottom:var(--space-xl)}
.empty .lids i{height:40px;border-radius:var(--radius-cell);background:var(--color-surface-sunken)}
.et{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);margin-bottom:var(--space-xs)!important}
''')

board('HomeRestored', 'SCR-004 기록 · 복원 뒤와 알림 꺼짐', h=1010, body=f'''
<div class="s {{{{fs}}}}">
  <div class="bar"><h1>기록</h1><button class="tb" type="button">지난 기록</button><button class="tb" type="button">설정</button></div>
  <div class="body">
    <div class="band">백업에서 복원했어요. 마지막 기록은 11/09&nbsp;21:40이에요. 그 뒤의 기록은 없을 수 있어요. 한 약은 한 휴대폰에서 기록해 주세요.
      <button class="btn ink sm" type="button">확인했어요</button></div>
    <div class="band" style="margin-top:var(--space-sm)">알림이 꺼져 있어 알려 드릴 수 없어요.
      <button class="btn line sm" type="button">설정 열기</button></div>
    <div class="hero"><span class="dow" aria-hidden="true">목</span><div><p class="d1">11월 12일 목요일</p><p class="sm muted"><b class="ink">0 / 4</b> 완료 · 하루 시작 04:00</p></div></div>
    <h2>오늘</h2>
    <div class="grid">
      <div class="t p1"><span class="who">나</span><span class="nm">아침약</span><span class="sub">매일 · 마지막 복용 11/09 08:02</span><span class="sp"></span><button class="btn p" type="button">먹었어</button></div>
      <div class="t p1"><span class="who">나</span><span class="nm">비타민D</span><span class="sub">매일 · 마지막 복용 11/09 08:05</span><span class="sp"></span><button class="btn p" type="button">먹었어</button></div>
      <div class="t p2"><span class="who">엄마</span><span class="nm">혈압약</span><span class="sub">매일 · 마지막 복용 11/09 21:40</span><span class="sp"></span><button class="btn p" type="button">먹었어</button></div>
      <div class="t p2"><span class="who">엄마</span><span class="nm">골다공증약</span><span class="sub">주 1회 월요일</span><span class="sub" style="font-weight: var(--font-weight-extrabold)">마지막 복용 11/02(월)&nbsp;08:10</span><span class="sp"></span><button class="btn p" type="button">먹었어</button></div>
    </div>
  </div>
</div>''', css='''
.band .btn.sm{margin-top:var(--space-sm);width:auto}
.hero{display:flex;align-items:center;gap:var(--space-md);padding:0 var(--space-2xs);margin-top:var(--space-xl)}
.d1{font-size:var(--font-size-title-md);font-weight:var(--font-weight-extrabold);line-height:1.3}
.ink{color:var(--color-ink-default)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:var(--space-sm)}
.t{border-radius:var(--radius-tile);padding:var(--space-md);display:flex;flex-direction:column;gap:2px;background:var(--p-tint);color:var(--p-ink);min-height:148px}
.t .who{font-size:var(--font-size-body-sm);font-weight:var(--font-weight-semibold)}
.t .nm{font-size:var(--font-size-title-sm);font-weight:var(--font-weight-extrabold);line-height:1.3}
.t .sub{font-size:var(--font-size-body-sm);line-height:1.35}
.t .sp{flex-grow:1}
.t .btn{margin-top:var(--space-sm)}
.fs200 .grid{grid-template-columns:1fr}
.fs200 .t{min-height:0}
''')
print('written')
