#!/usr/bin/env python3
"""DidITake 기획 검토 — 반례 실증 스크립트 (SQLite + 기간 계산).

실행: python3 -I evidence.py
- [재현] = 기획 그대로면 문제가 실제로 생기는지 확인 (PASS = 문제 재현됨)
- [해법] = 제안한 해법이 그 문제를 막는지 확인 (PASS = 막힘)
종료코드: 모든 단언이 성립하면 0, 하나라도 틀리면 1
"""
import sqlite3
import sys
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

SEOUL = ZoneInfo("Asia/Seoul")
RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}\n        -> {detail}")


def mem():
    return sqlite3.connect(":memory:", isolation_level=None)


def kst(y, mo, d, h=0, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=SEOUL)


def day_key(dt, day_start_h):
    """하루 경계(day_start_h시) 기준 '업무일' 키."""
    return (dt.astimezone(SEOUL) - timedelta(hours=day_start_h)).date().isoformat()


def anchored_week_start(dt, anchor_wd, day_start_h):
    """기준 요일(월=0)에 시작하는 7일 창의 시작일."""
    d = (dt.astimezone(SEOUL) - timedelta(hours=day_start_h)).date()
    return (d - timedelta(days=(d.weekday() - anchor_wd) % 7)).isoformat()


print(f"sqlite {sqlite3.sqlite_version} / python {sys.version.split()[0]}\n")

# ── T1. 이벤트 로그 + 유니크 제약 충돌 (§4 vs §6.4) ──────────────────────
c = mem()
c.execute("CREATE TABLE ev(id INTEGER PRIMARY KEY, med TEXT NOT NULL, period TEXT NOT NULL,"
          " type TEXT NOT NULL, UNIQUE(med, period))")
c.execute("INSERT INTO ev(med,period,type) VALUES('weekly-inj','2026-11-09','TAKEN')")
cur = c.execute("INSERT OR IGNORE INTO ev(med,period,type) VALUES('weekly-inj','2026-11-09','VOID')")
check("[재현] T1a 이벤트 로그 + UNIQUE(약,기간): 취소 이벤트 자체가 안 들어감",
      cur.rowcount == 0, f"VOID insert rowcount={cur.rowcount} (0 = 조용히 무시)")

c = mem()
c.execute("CREATE TABLE ev(id INTEGER PRIMARY KEY, med TEXT NOT NULL, period TEXT NOT NULL,"
          " type TEXT NOT NULL, UNIQUE(med, period, type))")
c.execute("INSERT INTO ev(med,period,type) VALUES('weekly-inj','2026-11-09','TAKEN')")
c.execute("INSERT INTO ev(med,period,type) VALUES('weekly-inj','2026-11-09','VOID')")
cur = c.execute("INSERT OR IGNORE INTO ev(med,period,type) VALUES('weekly-inj','2026-11-09','TAKEN')")
check("[재현] T1b UNIQUE(약,기간,유형)으로 바꿔도: 취소 후 재기록이 막힘",
      cur.rowcount == 0, f"취소 후 re-TAKEN rowcount={cur.rowcount}")

# ── T2. 해법: active(1|NULL) + UNIQUE(item, period, active) ───────────────
c = mem()
c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT NOT NULL, period TEXT NOT NULL,"
          " active INTEGER CHECK(active IS NULL OR active = 1), taken_at TEXT NOT NULL,"
          " voided_at TEXT, UNIQUE(item, period, active))")


def record(conn, item, period, at):
    return conn.execute("INSERT OR IGNORE INTO dose(item,period,active,taken_at) VALUES(?,?,1,?)",
                        (item, period, at)).rowcount


def void(conn, item, period, at):
    conn.execute("UPDATE dose SET active=NULL, voided_at=? WHERE item=? AND period=? AND active=1",
                 (at, item, period))


steps = [record(c, "weekly-inj", "2026-11-09", "08:30"),
         record(c, "weekly-inj", "2026-11-09", "08:30:01")]  # 연타
void(c, "weekly-inj", "2026-11-09", "09:00")
steps.append(record(c, "weekly-inj", "2026-11-09", "09:05"))  # 취소 후 재기록
void(c, "weekly-inj", "2026-11-09", "09:10")
steps.append(record(c, "weekly-inj", "2026-11-09", "09:15"))
total = c.execute("SELECT count(*) FROM dose").fetchone()[0]
active = c.execute("SELECT count(*) FROM dose WHERE active=1").fetchone()[0]
check("[해법] T2 NULL 트릭: 연타 무시 + 취소 후 재기록 허용 + 이력 보존 + 활성 1건",
      steps == [1, 0, 1, 1] and total == 3 and active == 1,
      f"insert 결과={steps} (1=기록,0=무시), 전체 행={total}, 활성 행={active}")

# ── T3. 부분 인덱스: SQLite 는 되지만 Room 은 엔티티로 선언 못 함 ────────
c = mem()
c.execute("CREATE TABLE d(id INTEGER PRIMARY KEY, item TEXT, period TEXT, voided_at TEXT)")
c.execute("CREATE UNIQUE INDEX one_active ON d(item, period) WHERE voided_at IS NULL")
r = [c.execute("INSERT OR IGNORE INTO d(item,period) VALUES('a','p')").rowcount,
     c.execute("INSERT OR IGNORE INTO d(item,period) VALUES('a','p')").rowcount]
c.execute("UPDATE d SET voided_at='x'")
r.append(c.execute("INSERT OR IGNORE INTO d(item,period) VALUES('a','p')").rowcount)
check("[참고] T3 SQLite 부분 유니크 인덱스도 같은 효과 (Room @Index 로는 선언 불가)",
      r == [1, 0, 1], f"insert 결과={r}")

# ── T4. INSERT OR IGNORE 의 '0건' 은 '중복' 확정이 아니다 ─────────────────
c = mem()
c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT NOT NULL, period TEXT NOT NULL,"
          " active INTEGER, taken_at TEXT NOT NULL, UNIQUE(item, period, active))")
cur = c.execute("INSERT OR IGNORE INTO dose(item,period,active,taken_at)"
                " VALUES('morning','2026-11-11',1,NULL)")
n = c.execute("SELECT count(*) FROM dose").fetchone()[0]
check("[재현] T4a OR IGNORE 는 NOT NULL 위반도 조용히 삼킴 → '이미 기록됨'과 신호가 같음",
      cur.rowcount == 0 and n == 0, f"rowcount={cur.rowcount}, 행={n}, 예외 없음")

c = mem()
c.execute("PRAGMA foreign_keys=ON")
c.execute("CREATE TABLE item(id TEXT PRIMARY KEY)")
c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT NOT NULL REFERENCES item(id),"
          " period TEXT NOT NULL, active INTEGER, UNIQUE(item, period, active))")
try:
    c.execute("INSERT OR IGNORE INTO dose(item,period,active) VALUES('deleted-item','2026-11-11',1)")
    fk_raised = False
except sqlite3.IntegrityError as e:
    fk_raised, err = True, str(e)
check("[재현] T4b 반대로 FK 위반은 OR IGNORE 가 못 삼킴 → 삭제된 약의 위젯 버튼 탭 = 예외",
      fk_raised, f"IntegrityError: {err if fk_raised else '-'}")

# ── T5. 동시 탭 경쟁 (check-then-insert 인터리빙) ─────────────────────────
for unique in (False, True):
    c = mem()
    uq = ", UNIQUE(item, period, active)" if unique else ""
    c.execute(f"CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT, period TEXT, active INTEGER{uq})")
    seen_a = c.execute("SELECT count(*) FROM dose WHERE item='m' AND period='p' AND active=1").fetchone()[0]
    seen_b = c.execute("SELECT count(*) FROM dose WHERE item='m' AND period='p' AND active=1").fetchone()[0]
    for seen in (seen_a, seen_b):  # 위젯·앱 둘 다 '없음'을 본 뒤 각자 insert
        if seen == 0:
            c.execute("INSERT OR IGNORE INTO dose(item,period,active) VALUES('m','p',1)")
    rows = c.execute("SELECT count(*) FROM dose").fetchone()[0]
    if unique:
        check("[해법] T5b 같은 인터리빙에 DB 유니크가 있으면 1건 (기획 §4 판단이 맞음)", rows == 1, f"행={rows}")
    else:
        check("[재현] T5a 앱 로직 체크만 있으면 위젯·앱 동시 탭에 2건", rows == 2, f"행={rows}")

# ── T6. 하루 경계 ─────────────────────────────────────────────────────────
a, b = kst(2026, 11, 11, 23, 59), kst(2026, 11, 12, 0, 1)
check("[재현] T6a 경계 00:00 이면 23:59 / 00:01 이 다른 날 → 2분 간격 2회 기록",
      day_key(a, 0) != day_key(b, 0), f"{day_key(a, 0)} vs {day_key(b, 0)}")
check("[해법] T6b 경계 04:00 이면 같은 날로 묶임",
      day_key(a, 4) == day_key(b, 4), f"{day_key(a, 4)} vs {day_key(b, 4)}")
a2, b2 = kst(2026, 11, 12, 3, 59), kst(2026, 11, 12, 4, 1)
check("[재현] T6c 경계는 옮겨질 뿐 사라지지 않음: 03:59 / 04:01 은 여전히 갈림",
      day_key(a2, 4) != day_key(b2, 4), f"{day_key(a2, 4)} vs {day_key(b2, 4)} → 상대시각 표시가 필요한 이유")

# ── T7. 주 1회의 '주' 정의 ────────────────────────────────────────────────
last_fri, now_mon = kst(2026, 11, 6, 8, 0), kst(2026, 11, 9, 8, 0)  # 금요일 약
cal = (anchored_week_start(last_fri, 0, 4), anchored_week_start(now_mon, 0, 4))
anc = (anchored_week_start(last_fri, 4, 4), anchored_week_start(now_mon, 4, 4))
check("[재현] T7a 달력 주(월~일): 금요일 약이 월요일부터 '미완료' → 조기 투약 유도",
      cal[0] != cal[1], f"지난 금 투약 주={cal[0]}, 이번 월 주={cal[1]} → 다른 주라 미완료로 보임")
check("[해법] T7b 기준 요일 창(금 04:00~다음 금 04:00): 월요일엔 아직 '완료' 유지",
      anc[0] == anc[1], f"둘 다 창 시작={anc[0]}")

late_sun, next_mon = kst(2026, 11, 15, 22, 0), kst(2026, 11, 16, 8, 0)  # 월요일 약을 일요일에 늦게
w1, w2 = anchored_week_start(late_sun, 0, 4), anchored_week_start(next_mon, 0, 4)
gap_h = (next_mon - late_sun).total_seconds() / 3600
check("[재현] T7c 월요일 약을 일요일 밤 늦게 → 다음 날 아침 새 창이 '미완료' (간격 10시간)",
      w1 != w2 and gap_h == 10, f"창 {w1} → {w2}, 직전 투약과 {gap_h:.0f}시간 → 위젯에 '마지막 어제 22:00' 필요")
rolling_next = (late_sun + timedelta(days=7)).strftime("%m-%d(%a)")
check("[재현] T7d 롤링 7일: 한 번 늦으면 요일이 영구히 밀림",
      rolling_next.endswith("(Sun)"), f"다음 기록 가능 {rolling_next} → 월요일 약이 일요일 약이 됨")

# ── T8. ISO 주번호 키 함정 (올해 2026 이 마침 53주 해) ─────────────────────
d1, d2 = date(2026, 12, 31), date(2027, 1, 1)
bad = (d1.strftime("%Y-W%V"), d2.strftime("%Y-W%V"))
good = (d1.strftime("%G-W%V"), d2.strftime("%G-W%V"))
check("[재현] T8 '달력연도+ISO주번호' 키: 같은 ISO 주가 연말연초에 키 2개로 갈림",
      d1.isocalendar()[:2] == d2.isocalendar()[:2] and bad[0] != bad[1] and good[0] == good[1],
      f"isocalendar 둘 다 {tuple(d1.isocalendar()[:2])}, 잘못된 키 {bad}, 올바른 키 {good}")

# ── T9. 주기 변경(매일 → 주1회) ──────────────────────────────────────────
c = mem()
c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT, period TEXT, active INTEGER,"
          " taken_at REAL, UNIQUE(item, period, active))")
taken = kst(2026, 11, 11, 8, 0)  # 수요일, 매일 약으로 기록
c.execute("INSERT INTO dose(item,period,active,taken_at) VALUES('x',?,1,?)",
          (day_key(taken, 4), taken.timestamp()))
change = kst(2026, 11, 11, 14, 0)  # 같은 날 오후 '주1회(월)'로 변경
new_key = anchored_week_start(change, 0, 4)
dup_ok = c.execute("INSERT OR IGNORE INTO dose(item,period,active,taken_at) VALUES('x',?,1,?)",
                   (new_key, change.timestamp())).rowcount
check("[재현] T9a 키만 보면: 주기 바꾼 당일 같은 약 2회 기록이 통과",
      dup_ok == 1, f"기존 키 {day_key(taken, 4)}, 새 키 {new_key} → 유니크가 못 막음")
c.execute("DELETE FROM dose WHERE period=?", (new_key,))
ws = datetime.fromisoformat(new_key).replace(hour=4, tzinfo=SEOUL)
we = ws + timedelta(days=7)
hit = c.execute("SELECT count(*) FROM dose WHERE item='x' AND active=1 AND taken_at>=? AND taken_at<?",
                (ws.timestamp(), we.timestamp())).fetchone()[0]
check("[해법] T9b 현재 창 [시작,끝) 안에 활성 기록이 있는지(taken_at)로 판정하면 막힘",
      hit == 1, f"창 {ws:%m-%d %H:%M} ~ {we:%m-%d %H:%M} 안 활성 기록 {hit}건 → '이미 기록됨'")

# ── T10. 갱신 안 된 위젯(stale) 탭 ────────────────────────────────────────
rendered_key = day_key(kst(2026, 11, 11, 8, 2), 4)  # 어제 ✓ 상태로 그려진 위젯
tap = kst(2026, 11, 12, 7, 50)  # 경계 갱신이 밀린 채 '확인하려고' 탭
naive = "RECORD" if day_key(tap, 4) != rendered_key else "IGNORE"
guarded = "REFRESH" if day_key(tap, 4) != rendered_key else "PROCEED"
check("[재현] T10a 렌더 시점 기간을 안 보면: 어제의 ✓ 를 탭하는 순간 오늘 몫이 거짓 기록",
      naive == "RECORD", f"렌더 {rendered_key} / 탭 시점 {day_key(tap, 4)} → {naive}")
check("[해법] T10b 액션 파라미터에 렌더 기간키를 넣고 불일치면 기록 대신 새로고침",
      guarded == "REFRESH", f"→ {guarded}")

# ── T11. v2 '주기 회차'를 상대 번호로 두면 기준일 이동(6.2) 때 충돌 ─────────
def cycle_idx(dt, anchor):
    return str(int((dt - anchor).total_seconds() // (7 * 86400)))

for key_mode in ("idx", "date"):
    c = mem()
    c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT, period TEXT, active INTEGER,"
              " UNIQUE(item, period, active))")
    v1 = kst(2026, 11, 2, 4, 0)  # 버전1: 월요일 기준
    for t in (kst(2026, 11, 2, 8), kst(2026, 11, 9, 8), kst(2026, 11, 18, 8)):  # 마지막은 이틀 늦은 수요일
        k = cycle_idx(t, v1) if key_mode == "idx" else anchored_week_start(t, 0, 4)
        c.execute("INSERT INTO dose(item,period,active) VALUES('weekly-inj',?,1)", (k,))
    v2 = kst(2026, 11, 18, 4, 0)  # 늦게 맞은 날로 기준일 이동 → 버전2: 수요일 기준
    nxt = kst(2026, 11, 25, 8)
    k = cycle_idx(nxt, v2) if key_mode == "idx" else anchored_week_start(nxt, 2, 4)
    rc = c.execute("INSERT OR IGNORE INTO dose(item,period,active) VALUES('weekly-inj',?,1)", (k,)).rowcount
    if key_mode == "idx":
        check("[재현] T11a 회차=상대 번호: 기준일 옮긴 뒤 다음 주 실제 투약이 '이미 기록됨'으로 버려짐",
              rc == 0, f"새 버전 회차 키 '{k}' 가 옛 버전 11-09 회차 키 '1' 과 충돌 → rowcount={rc}")
    else:
        check("[해법] T11b 키 = 창 시작 날짜(절대값)면 충돌 없음",
              rc == 1, f"키 '{k}' → rowcount={rc}")

# ── T12. v2 건너뜀 이벤트: '한 기간 = 활성 판정 1개' 로 묶기 ────────────────
c = mem()
c.execute("CREATE TABLE dose(id INTEGER PRIMARY KEY, item TEXT, period TEXT, kind TEXT,"
          " active INTEGER, UNIQUE(item, period, active))")
s1 = c.execute("INSERT OR IGNORE INTO dose(item,period,kind,active) VALUES('m','2026-11-11','SKIPPED',1)").rowcount
w = c.execute("INSERT OR IGNORE INTO dose(item,period,kind,active) VALUES('m','2026-11-11','TAKEN',1)").rowcount
c.execute("UPDATE dose SET active=NULL WHERE item='m' AND period='2026-11-11' AND active=1")  # 앱에서 '건너뜀 → 복용'으로 변경
a = c.execute("INSERT OR IGNORE INTO dose(item,period,kind,active) VALUES('m','2026-11-11','TAKEN',1)").rowcount
rows = c.execute("SELECT kind, active FROM dose ORDER BY id").fetchall()
check("[해법] T12 건너뜀 칸을 위젯에서 탭 → 변경 없음 / 앱에서 바꾸면 이력 남기고 복용으로",
      (s1, w, a) == (1, 0, 1) and rows == [("SKIPPED", None), ("TAKEN", 1)],
      f"건너뜀={s1}, 위젯 탭={w}(무시), 앱 변경={a}, 행={rows}")

ok = sum(RESULTS)
print(f"\n== {ok}/{len(RESULTS)} 단언 성립 ==")
sys.exit(0 if ok == len(RESULTS) else 1)
