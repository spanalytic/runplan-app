#!/usr/bin/env python3
"""runplan - a free, rules-based Runna-style plan generator.

Race time -> VDOT (Daniels/Gilbert) -> training paces -> periodised plan.
Outputs:
  plan.csv                      one row per session
  workouts/<name>.json          RunnerUp structured workouts (pace targets per step)
  workouts.zip                  the same, zipped for transfer to the phone

Stdlib only. Run `python runplan.py -h` for options.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import zipfile
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

# --------------------------------------------------------------------------- #
# VDOT model (Daniels & Gilbert, "Oxygen Power", 1979)
# --------------------------------------------------------------------------- #

DISTANCES = {
    "1500": 1500.0, "mile": 1609.344, "3k": 3000.0, "5k": 5000.0, "10k": 10000.0,
    "15k": 15000.0, "10mi": 16093.44, "half": 21097.5, "marathon": 42195.0,
}


def vo2_at(v: float) -> float:
    """O2 cost (ml/kg/min) of running at v metres/min."""
    return -4.60 + 0.182258 * v + 0.000104 * v * v


def pct_max(t: float) -> float:
    """Fraction of VO2max sustainable for t minutes."""
    return 0.8 + 0.1894393 * math.exp(-0.012778 * t) + 0.2989558 * math.exp(-0.1932605 * t)


def vdot(dist_m: float, t_sec: float) -> float:
    t = t_sec / 60.0
    return vo2_at(dist_m / t) / pct_max(t)


def velocity_for_vo2(vo2: float) -> float:
    """Inverse of vo2_at: metres/min."""
    a, b, c = 0.000104, 0.182258, -(4.60 + vo2)
    return (-b + math.sqrt(b * b - 4 * a * c)) / (2 * a)


def predict_time(v_dot: float, dist_m: float) -> float:
    """Race time (s) for dist_m at a given VDOT (bisection)."""
    lo, hi = 60.0, 60.0 * 60 * 10
    for _ in range(100):
        mid = (lo + hi) / 2
        if vdot(dist_m, mid) > v_dot:
            lo = mid  # too fast -> needs more time
        else:
            hi = mid
    return (lo + hi) / 2


def pace_at_fraction(v_dot: float, frac: float) -> float:
    """Pace (s/km) at a fraction of VO2max."""
    return 60000.0 / velocity_for_vo2(v_dot * frac)


@dataclass
class Paces:
    vdot: float
    easy: tuple[float, float]      # (fast, slow) s/km
    marathon: float
    half: float
    threshold: float
    interval: float
    rep: float
    goal: float = 0.0              # goal-race pace at current fitness, s/km

    @classmethod
    def from_vdot(cls, v: float) -> "Paces":
        return cls(
            vdot=v,
            easy=(pace_at_fraction(v, 0.72), pace_at_fraction(v, 0.62)),
            marathon=predict_time(v, DISTANCES["marathon"]) / 42.195,
            half=predict_time(v, DISTANCES["half"]) / 21.0975,
            threshold=pace_at_fraction(v, 0.88),
            interval=pace_at_fraction(v, 0.975),
            rep=pace_at_fraction(v, 1.055),
        )

    def zone(self, name: str) -> tuple[float, float]:
        """(fast, slow) target band in s/km for a named zone."""
        if name == "E":
            return self.easy
        centre = {"M": self.marathon, "HM": self.half, "T": self.threshold,
                  "I": self.interval, "R": self.rep, "G": self.goal}[name]
        tol = 5
        return (centre - tol, centre + tol)

    def centre(self, name: str) -> float:
        f, s = self.zone(name)
        return (f + s) / 2


def fmt_pace(s: float) -> str:
    s = round(s)
    return f"{s // 60}:{s % 60:02d}"


def fmt_time(s: float) -> str:
    s = round(s)
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m}:{sec:02d}"


def parse_time(txt: str) -> float:
    parts = [float(p) for p in txt.split(":")]
    sec = 0.0
    for p in parts:
        sec = sec * 60 + p
    return sec


# --------------------------------------------------------------------------- #
# Workout model
# --------------------------------------------------------------------------- #

@dataclass
class Step:
    kind: str                 # warmup | interval | recovery | cooldown | rest
    secs: float | None = None
    metres: float | None = None
    zone: str | None = None   # E, M, HM, T, I, R or None (no target)
    hill: bool = False        # uphill by effort (recovery: back down); no pace target


@dataclass
class Repeat:
    times: int
    steps: list[Step]


@dataclass
class Workout:
    day: date
    week: int
    phase: str
    session: str              # Easy | Long | Intervals | Threshold | ...
    blocks: list = field(default_factory=list)
    note: str = ""

    # -- estimates --------------------------------------------------------- #
    def _flat(self):
        for b in self.blocks:
            if isinstance(b, Repeat):
                for _ in range(b.times):
                    yield from b.steps
            else:
                yield b

    def estimate(self, p: Paces) -> tuple[float, float]:
        km = mins = 0.0
        for s in self._flat():
            pace = p.centre(s.zone) if s.zone else p.easy[1] + 20  # jog
            if s.metres is not None:
                km += s.metres / 1000
                mins += s.metres / 1000 * pace / 60
            elif s.secs is not None:
                mins += s.secs / 60
                km += s.secs / pace
        return km, mins

    def describe(self, p: Paces) -> str:
        def one(s: Step) -> str:
            amt = (f"{s.metres / 1000:g} km" if s.metres is not None
                   else f"{s.secs / 60:g} min")
            if s.zone is None:
                if s.hill:
                    return f"{amt} back down" if s.kind == "recovery" else f"{amt} hard uphill"
                return f"{amt} jog" if s.kind in ("recovery", "rest") else amt
            f, sl = p.zone(s.zone)
            return f"{amt} @ {s.zone} {fmt_pace(f)}-{fmt_pace(sl)}/km"

        parts = []
        for b in self.blocks:
            if isinstance(b, Repeat):
                inner = " + ".join(one(s) for s in b.steps)
                parts.append(f"{b.times}x ({inner})")
            else:
                parts.append(one(b))
        return " | ".join(parts)

    @property
    def filename(self) -> str:
        return f"W{self.week:02d}-{self.day:%a}-{self.session}"

    # -- RunnerUp export ----------------------------------------------------- #
    def runnerup_json(self, p: Paces) -> dict:
        """RunnerUp's Garmin-style workout JSON (see WorkoutSerializer.java)."""
        steps: list[dict] = []
        order = 0
        group = 1

        def step_json(s: Step, parent: int | None) -> dict:
            nonlocal order
            order += 1
            d: dict = {"stepOrder": order, "groupId": parent or group,
                       "stepTypeKey": s.kind}
            if parent is not None:
                d["parentGroupId"] = parent
            if s.metres is not None:
                d.update(endConditionTypeKey="distance",
                         endConditionValue=round(s.metres * 100),
                         endConditionUnitKey="centimeter")
            elif s.secs is not None:
                d.update(endConditionTypeKey="time",
                         endConditionValue=round(s.secs * 1000),
                         endConditionUnitKey="ms")
            else:
                d.update(endConditionTypeKey="lap.button",
                         endConditionUnitKey="dimensionless")
            if s.zone:
                fast, slow = p.zone(s.zone)
                # RunnerUp stores pace zones as speeds in cm/ms: (slow, fast)
                d.update(targetTypeKey="pace.zone",
                         targetValueOne=100.0 / slow,
                         targetValueTwo=100.0 / fast,
                         targetValueUnitKey="centimetersPerMillisecond")
            else:
                d.update(targetTypeKey="no.target", targetValueUnitKey="dimensionless")
            return d

        for b in self.blocks:
            if isinstance(b, Repeat):
                group += 1
                order += 1
                rep_group = group
                steps.append({"stepOrder": order, "groupId": rep_group,
                              "stepTypeKey": "repeat",
                              "endConditionTypeKey": "iterations",
                              "endConditionValue": b.times,
                              "endConditionUnitKey": "dimensionless"})
                for s in b.steps:
                    steps.append(step_json(s, rep_group))
                group += 1
            else:
                steps.append(step_json(b, None))
        return {"com.garmin.connect.workout.json.UserWorkoutJson": {"workoutSteps": steps}}


# --------------------------------------------------------------------------- #
# Plan rules
# --------------------------------------------------------------------------- #

GOALS = {
    #          peak km @5 days, long-run cap km, taper weeks (multipliers)
    "5k":       (40, 13, [0.65]),
    "10k":      (45, 16, [0.65]),
    "half":     (52, 19, [0.75, 0.5]),
    "marathon": (68, 32, [0.8, 0.65, 0.45]),
}

DAY_NAMES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
DEFAULT_DAYS = {
    3: ["tue", "thu", "sun"],
    4: ["tue", "wed", "fri", "sun"],
    5: ["tue", "wed", "thu", "sat", "sun"],
    6: ["tue", "wed", "thu", "fri", "sat", "sun"],
}


def assign_roles(days: list[str]) -> dict[str, str]:
    """first day = Q1, last = Long, Q2 (4+ days) spaced from both, rest = Easy."""
    n = len(days)
    roles = {d: "Easy" for d in days}
    roles[days[0]] = "Q1"
    roles[days[-1]] = "Long"
    if n >= 4:
        # put Q2 on the free day furthest (circularly) from both Q1 and the long run
        def gap(a: str, b: str) -> int:
            d = abs(DAY_NAMES.index(a) - DAY_NAMES.index(b))
            return min(d, 7 - d)
        free = days[1:-1]
        roles[max(free, key=lambda d: (min(gap(d, days[0]), gap(d, days[-1])), -free.index(d)))] = "Q2"
    return roles


def r5(x: float) -> float:
    """Round km to nearest 0.5."""
    return max(0.5, round(x * 2) / 2)


def phase_for(i: int, build_weeks: int) -> tuple[str, float]:
    """Phase name and progress (0..1) within that phase for week index i."""
    base = max(1, round(build_weeks * 0.3))
    build = max(1, round(build_weeks * 0.45))
    peak = max(1, build_weeks - base - build)
    for name, length, start in (("Base", base, 0), ("Build", build, base),
                                ("Peak", peak, base + build)):
        if i < start + length:
            return name, (i - start) / max(1, length - 1)
    return "Peak", 1.0


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def q1_session(phase: str, t: float, goal: str, taper: bool) -> tuple[str, list]:
    """VO2max / speed day."""
    wu, cd = Step("warmup", secs=15 * 60, zone="E"), Step("cooldown", secs=10 * 60, zone="E")
    if taper:
        return "Intervals", [wu, Repeat(3, [Step("interval", secs=180, zone="I"),
                                            Step("recovery", secs=120)]), cd]
    if phase == "Base":
        n = round(lerp(6, 10, t))
        return "Strides", [wu, Repeat(n, [Step("interval", secs=60, zone="R"),
                                          Step("recovery", secs=120)]), cd]
    # Build / Peak: I-pace reps, growing rep length and total time
    rep_min = round(lerp(3, 5, t)) if phase == "Build" else 5
    total = lerp(12, 20, t) if phase == "Build" else lerp(20, 25, t)
    if goal in ("half", "marathon") and phase == "Peak":
        total = 20
    n = max(3, round(total / rep_min))
    rec = max(60, round(rep_min * 0.4) * 60)   # 5' rep -> 2' jog
    return "Intervals", [wu, Repeat(n, [Step("interval", secs=rep_min * 60, zone="I"),
                                        Step("recovery", secs=rec)]), cd]


def hill_session(phase: str, t: float, i: int, taper: bool, terrain: str, hills: bool) -> tuple[str, list] | None:
    """Hill session replacing the week's Q1, or None. Base: hill sprints every other week in
    place of strides. Build: hill repeats every 4th/3rd/2nd week (flat/rolling/hilly race).
    Peak: hilly races keep hill repeats every 3rd week."""
    if not hills or taper:
        return None
    wu, cd = Step("warmup", secs=15 * 60, zone="E"), Step("cooldown", secs=10 * 60, zone="E")

    def repeats(rep: float, n: int):
        down = max(60, round(rep * 1.5 / 15) * 15)
        return "HillRepeats", [wu, Repeat(n, [Step("interval", secs=rep, hill=True),
                                              Step("recovery", secs=down, hill=True)]), cd]
    if phase == "Base":
        if i % 2:
            return None
        n = round(lerp(6, 10, t))
        return "HillSprints", [wu, Repeat(n, [Step("interval", secs=10, hill=True),
                                              Step("recovery", secs=120, hill=True)]), cd]
    if phase == "Build":
        every = {"flat": 4, "rolling": 3, "hilly": 2}[terrain]
        return None if i % every else repeats(round(lerp(60, 90, t) / 15) * 15, round(lerp(6, 10, t)))
    if phase == "Peak" and terrain == "hilly" and i % 3 == 0:
        return repeats(90, 8)
    return None


HILL_NOTES = {
    "HillSprints": "Find a short, steep hill. Sprint up hard, walk back down and recover fully.",
    "HillRepeats": "Find a steady hill. Run up at a hard effort, jog back down. Run by effort, not pace.",
}


def q2_session(phase: str, t: float, goal: str, taper: bool) -> tuple[str, list]:
    """Threshold / race-specific day."""
    wu, cd = Step("warmup", secs=12 * 60, zone="E"), Step("cooldown", secs=10 * 60, zone="E")
    if taper:
        return "Threshold", [wu, Repeat(2, [Step("interval", secs=8 * 60, zone="T"),
                                            Step("recovery", secs=90)]), cd]
    if phase == "Base":
        return "Threshold", [wu, Repeat(3, [Step("interval", secs=5 * 60, zone="T"),
                                            Step("recovery", secs=60)]), cd]
    if phase == "Build":
        rep = round(lerp(6, 10, t))
        n = 4 if rep <= 8 else 3
        return "Threshold", [wu, Repeat(n, [Step("interval", secs=rep * 60, zone="T"),
                                            Step("recovery", secs=90)]), cd]
    # Peak: race-specific
    if goal == "marathon":
        km = round(lerp(8, 14, t))
        return "MarathonPace", [wu, Step("interval", metres=km * 1000, zone="M"), cd]
    if goal == "half":
        km = round(lerp(5, 8, t))
        return "HalfPace", [wu, Step("interval", metres=km * 1000, zone="HM"), cd]
    mins = round(lerp(15, 25, t))
    return "Tempo", [wu, Step("interval", secs=mins * 60, zone="T"), cd]


def long_session(km: float, phase: str, t: float, goal: str, week: int) -> tuple[str, list]:
    if goal == "marathon" and phase in ("Build", "Peak") and week % 2 == 0:
        fin = r5(min(km * 0.35, lerp(5, 12, t) if phase == "Peak" else 5))
        return "LongFastFinish", [Step("interval", metres=(km - fin) * 1000, zone="E"),
                                  Step("interval", metres=fin * 1000, zone="M")]
    if goal == "half" and phase == "Peak" and week % 2 == 0:
        fin = r5(min(km * 0.25, lerp(3, 5, t)))
        return "LongFastFinish", [Step("interval", metres=(km - fin) * 1000, zone="E"),
                                  Step("interval", metres=fin * 1000, zone="HM")]
    return "Long", [Step("interval", metres=km * 1000, zone="E")]


def build_plan(paces: Paces, goal: str, start: date, race_day: date,
               days: list[str], current_km: float, peak_km: float | None,
               terrain: str = "flat", hills: bool = False) -> list[Workout]:
    peak_default, long_cap, taper_mults = GOALS[goal]
    n_days = len(days)
    roles = assign_roles(days)
    week0 = start - timedelta(days=start.weekday())  # Monday
    n_weeks = (race_day - week0).days // 7 + 1
    taper_n = len(taper_mults)
    build_weeks = n_weeks - taper_n
    if build_weeks < 3:
        raise SystemExit(f"Need at least {taper_n + 3} weeks to race day; got {n_weeks}.")

    peak = peak_km or peak_default * n_days / 5
    peak = max(peak, current_km)
    long_share = {3: 0.40, 4: 0.36, 5: 0.32, 6: 0.28}[min(6, max(3, n_days))]

    # weekly volumes: +<=10%/wk towards peak, every 4th week a 20% cut-back
    vols, v = [], current_km
    for i in range(build_weeks):
        if i > 0:
            v = min(peak, v * 1.10) if (i % 4) != 0 else v
        vols.append(v * (0.8 if (i % 4 == 3 and i < build_weeks - 1) else 1.0))
    vols += [peak * m for m in taper_mults]

    plan: list[Workout] = []
    for i in range(n_weeks):
        monday = week0 + timedelta(weeks=i)
        taper = i >= build_weeks
        phase, t = ("Taper", 1.0) if taper else phase_for(i, build_weeks)
        is_down = (not taper) and i % 4 == 3 and i < build_weeks - 1
        week_km = vols[i]
        long_km = r5(min(long_cap, max(6.0, week_km * long_share)))
        if taper:
            long_km = r5(min(long_km, long_cap * (0.6 if i < n_weeks - 1 else 0)))

        sessions: list[Workout] = []
        for d in days:
            day = monday + timedelta(days=DAY_NAMES.index(d))
            if day < start or day > race_day:
                continue
            role = roles[d]
            race_week = monday <= race_day < monday + timedelta(days=7)
            if role == "Q2" and (is_down or race_week):
                role = "Easy"                       # one quality day only in cut-back / race weeks
            w = Workout(day, i + 1, phase, "")
            if role == "Q1":
                w.session, w.blocks = (hill_session(phase, t, i, taper, terrain, hills)
                                       or q1_session(phase, t, goal, taper))
                w.note = HILL_NOTES.get(w.session, "")
            elif role == "Q2":
                w.session, w.blocks = q2_session(phase, t, goal, taper)
            elif role == "Long":
                if long_km <= 0:
                    continue
                w.session, w.blocks = long_session(long_km, phase, t, goal, i + 1)
                if terrain == "hilly" and phase in ("Build", "Peak"):
                    w.note = "Pick a hilly route if you can."
            else:
                w.session = "Easy"
            sessions.append(w)

        # race-week: race replaces whatever is on race day; drop last session before it if same day
        if monday <= race_day < monday + timedelta(days=7):
            sessions = [s for s in sessions if s.day != race_day]
            dist = DISTANCES[goal]
            race = Workout(race_day, i + 1, "Race", "Race",
                           [Step("warmup", secs=10 * 60, zone="E"),
                            Step("interval", metres=dist, zone="G")])
            race.note = f"Predicted {fmt_time(predict_time(paces.vdot, dist))}"
            sessions.append(race)
            # 2 days before race at most an easy shakeout
            sessions = [s for s in sessions
                        if not (0 < (race_day - s.day).days <= 2 and s.session != "Easy")]

        # fill easy runs with the remaining volume
        fixed = sum(s.estimate(paces)[0] for s in sessions if s.session != "Easy")
        easies = [s for s in sessions if s.session == "Easy"]
        if easies:
            remaining = max(0.0, week_km - fixed)
            each = r5(min(max(4.0, remaining / len(easies)), max(6.0, long_km * 0.75)))
            if taper:
                each = r5(max(4.0, min(each, 6.0)))
            for s in easies:
                km = each
                if 0 < (race_day - s.day).days <= 2:
                    km = min(km, 4.0)
                    s.note = "Shakeout: add 4x20s strides"
                s.blocks = [Step("interval", metres=km * 1000, zone="E")]
        if is_down:
            for s in sessions:
                s.note = (s.note + " " if s.note else "") + "Cut-back week"
        plan.extend(sorted(sessions, key=lambda s: s.day))
    return plan


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def next_monday(d: date) -> date:
    return d + timedelta(days=(7 - d.weekday()) % 7 or 7)


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--recent", required=True,
                    help="recent race/time trial as DIST=TIME, e.g. 10k=52:30, 5k=24:10, half=1:55:00")
    ap.add_argument("--goal", required=True, choices=GOALS, help="goal race distance")
    ap.add_argument("--race-date", type=date.fromisoformat, help="YYYY-MM-DD (default: start + --weeks)")
    ap.add_argument("--weeks", type=int, default=12, help="plan length if --race-date not given")
    ap.add_argument("--start", type=date.fromisoformat, help="first day of plan (default: next Monday)")
    ap.add_argument("--days", default=None,
                    help="comma list of run days, e.g. tue,thu,sat,sun (first=intervals, last=long)")
    ap.add_argument("--runs-per-week", type=int, default=4, choices=[3, 4, 5, 6])
    ap.add_argument("--current-km", type=float, default=25, help="current weekly km")
    ap.add_argument("--peak-km", type=float, help="override peak weekly km")
    ap.add_argument("--terrain", default="flat", choices=["flat", "rolling", "hilly"], help="how hilly the race is")
    ap.add_argument("--hills", action="store_true", help="add hill sprints / hill repeats")
    ap.add_argument("--from-date", type=date.fromisoformat,
                    help="only export sessions on/after this date (use after a recalibration)")
    ap.add_argument("--out", default="plan_out", help="output directory")
    a = ap.parse_args(argv)

    dist_key, t_txt = a.recent.split("=")
    if dist_key not in DISTANCES:
        raise SystemExit(f"distance must be one of {', '.join(DISTANCES)}")
    v = vdot(DISTANCES[dist_key], parse_time(t_txt))
    paces = Paces.from_vdot(v)
    paces.goal = predict_time(v, DISTANCES[a.goal]) / (DISTANCES[a.goal] / 1000)

    start = a.start or next_monday(date.today())
    race_day = a.race_date or (start + timedelta(weeks=a.weeks) - timedelta(days=start.weekday() + 1))
    days = [d.strip().lower()[:3] for d in a.days.split(",")] if a.days else DEFAULT_DAYS[a.runs_per_week]
    days = sorted(set(days), key=DAY_NAMES.index)
    if len(days) < 3:
        raise SystemExit("need at least 3 run days")

    plan = build_plan(paces, a.goal, start, race_day, days, a.current_km, a.peak_km, a.terrain, a.hills)
    if a.from_date:
        plan = [w for w in plan if w.day >= a.from_date]

    out = Path(a.out)
    wdir = out / "workouts"
    wdir.mkdir(parents=True, exist_ok=True)
    for f in wdir.glob("*.json"):
        f.unlink()

    rows = []
    for w in plan:
        km, mins = w.estimate(paces)
        (wdir / f"{w.filename}.json").write_text(json.dumps(w.runnerup_json(paces), indent=1))
        rows.append({
            "date": w.day.isoformat(), "dow": f"{w.day:%a}", "week": w.week, "phase": w.phase,
            "session": w.session, "est_km": round(km, 1), "est_min": round(mins),
            "workout": w.describe(paces), "note": w.note, "runnerup_file": f"{w.filename}.json",
        })
    with open(out / "plan.csv", "w", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0]))
        wr.writeheader()
        wr.writerows(rows)

    with zipfile.ZipFile(out / "workouts.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(wdir.glob("*.json")):
            z.write(f, f.name)

    with open(out / "paces.csv", "w", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["zone", "fast_per_km", "slow_per_km", "use"])
        uses = {"E": "easy & long runs", "M": "marathon pace", "HM": "half-marathon pace",
                "T": "threshold / tempo", "I": "VO2max intervals", "R": "repetitions / strides"}
        uses["G"] = f"{a.goal} race pace"
        for z in ["E", "M", "HM", "T", "I", "R", "G"]:
            f, s = paces.zone(z)
            wr.writerow([z, fmt_pace(f), fmt_pace(s), uses[z]])

    # weekly summary to stdout
    print(f"VDOT {v:.1f} from {a.recent}")
    for z in ["E", "M", "HM", "T", "I", "R"]:
        f, s = paces.zone(z)
        print(f"  {z:>2}: {fmt_pace(f)}-{fmt_pace(s)}/km")
    print("Predicted: " + ", ".join(f"{k} {fmt_time(predict_time(v, DISTANCES[k]))}"
                                    for k in ("5k", "10k", "half", "marathon")))
    print(f"\n{len(plan)} sessions, {start} -> {race_day}  ({out}/plan.csv, {out}/workouts.zip)")
    weeks: dict[int, list] = {}
    for r in rows:
        weeks.setdefault(r["week"], []).append(r)
    for wk, rs in weeks.items():
        print(f"  W{wk:02d} {rs[0]['phase']:<5} {sum(r['est_km'] for r in rs):5.1f} km  "
              + ", ".join(f"{r['dow']} {r['session']}" for r in rs))


if __name__ == "__main__":
    main()
