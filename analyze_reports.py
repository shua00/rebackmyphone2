#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeviceFinder 上报数据分析（纯命令行，无第三方依赖）

用法:
    python analyze_reports.py                              # 最近 30 天所有设备，输出汇总
    python analyze_reports.py --days 7                    # 最近 7 天
    python analyze_reports.py --device Redmi_Note_8_xxx   # 单设备
    python analyze_reports.py --health                    # 健康分析（上报间隔、失联、坐标跳变）
    python analyze_reports.py --hourly                    # 24 小时热力图
    python analyze_reports.py --commute                   # 上下班时间推断
    python analyze_reports.py --fields sim_state,rssi     # 查看指定字段的分布
    python analyze_reports.py --export out.csv            # 导出 CSV

    python analyze_reports.py --all                       # 全部分析（汇总+健康+热力图+通勤）
"""
import sqlite3
import sys
import os
import csv
import math
from datetime import datetime, timedelta
from collections import defaultdict, Counter

DB_FILE = "track_history.db"
REPORT_INTERVAL_SEC = 1800
STALE_THRESHOLD_SEC = 7200


def conn():
    if not os.path.isfile(DB_FILE):
        print(f"❌ 数据库不存在: {os.path.abspath(DB_FILE)}")
        sys.exit(1)
    c = sqlite3.connect(DB_FILE, timeout=10)
    c.row_factory = sqlite3.Row
    return c


def parse_args():
    args = {"days": 30, "device": None, "export": None,
            "health": False, "hourly": False, "commute": False,
            "fields": None, "all": False}
    i = 1
    while i < len(sys.argv):
        a = sys.argv[i]
        if a == "--days" and i+1 < len(sys.argv): args["days"] = int(sys.argv[i+1]); i += 2
        elif a == "--device" and i+1 < len(sys.argv): args["device"] = sys.argv[i+1]; i += 2
        elif a == "--export" and i+1 < len(sys.argv): args["export"] = sys.argv[i+1]; i += 2
        elif a == "--fields" and i+1 < len(sys.argv): args["fields"] = sys.argv[i+1]; i += 2
        elif a == "--health": args["health"] = True; i += 1
        elif a == "--hourly": args["hourly"] = True; i += 1
        elif a == "--commute": args["commute"] = True; i += 1
        elif a == "--all": args["all"] = True; i += 1
        else:
            print(f"⚠️ 未知参数: {a}")
            i += 1
    if args["all"]:
        args["health"] = args["hourly"] = args["commute"] = True
    return args


def check_reports_table(c):
    try:
        c.execute("SELECT 1 FROM reports LIMIT 1")
        return True
    except sqlite3.OperationalError:
        return False


def get_available_columns(c):
    try:
        return {row[1] for row in c.execute("PRAGMA table_info(reports)").fetchall()}
    except Exception:
        return set()


def section(title):
    print(f"\n{'=' * 62}")
    print(f"  {title}")
    print(f"{'=' * 62}")


def haversine_km(lat1, lng1, lat2, lng2):
    R = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1); dl = math.radians(lng2 - lng1)
    a = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(math.sqrt(a))


# ==================== 分析模块 ====================

def analyze_daily(c, device, days):
    section(f"📅 每日上报汇总（最近 {days} 天）")
    start = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")
    if device:
        rows = c.execute("""
            SELECT day_str, COUNT(*),
                   ROUND(AVG(battery_level), 0),
                   MIN(battery_level), MAX(battery_level),
                   SUM(CASE WHEN is_charging=1 THEN 1 ELSE 0 END),
                   ROUND(AVG(NULLIF(location_age_s, -1)), 0)
            FROM reports
            WHERE device_id=? AND day_str>=? AND day_str IS NOT NULL
            GROUP BY day_str ORDER BY day_str
        """, (device, start)).fetchall()
        print(f"\n{'日期':<12}{'条数':>6}{'均电量':>8}{'最低':>6}{'最高':>6}{'充电':>6}{'均定位年龄s':>14}")
        print("-" * 62)
        for r in rows:
            print(f"{r[0]:<12}{r[1]:>6}{r[2] or '-':>8}{r[3] or '-':>6}{r[4] or '-':>6}{r[5] or 0:>6}{r[6] or '-':>14}")
    else:
        rows = c.execute("""
            SELECT day_str, device_id, COUNT(*)
            FROM reports WHERE day_str>=?
            GROUP BY day_str, device_id ORDER BY day_str
        """, (start,)).fetchall()
        by_day = defaultdict(dict)
        for r in rows:
            by_day[r[0]][r[1]] = r[2]
        for day in sorted(by_day.keys()):
            parts = [f"{dev}={cnt}" for dev, cnt in sorted(by_day[day].items())]
            total = sum(by_day[day].values())
            print(f"{day}  合计 {total:>4}  |  {' | '.join(parts)}")


def analyze_health(c, device, days):
    section(f"🩺 健康分析（最近 {days} 天）")
    start_ms = int((datetime.now() - timedelta(days=days)).timestamp() * 1000)
    if device:
        rows = c.execute("""
            SELECT timestamp, lat, lng, accuracy, network_type,
                   battery_level, location_age_s, day_str, hour
            FROM reports WHERE device_id=? AND timestamp>=? ORDER BY timestamp
        """, (device, start_ms)).fetchall()
        devices = {device: rows}
    else:
        devices_raw = c.execute("SELECT DISTINCT device_id FROM reports WHERE timestamp>=?", (start_ms,)).fetchall()
        devices = {}
        for d in devices_raw:
            dev_id = d[0]
            devices[dev_id] = c.execute("""
                SELECT timestamp, lat, lng, accuracy, network_type,
                       battery_level, location_age_s, day_str, hour
                FROM reports WHERE device_id=? AND timestamp>=? ORDER BY timestamp
            """, (dev_id, start_ms)).fetchall()

    if not devices:
        print("⚠️ 无数据")
        return

    for dev, rows in devices.items():
        print(f"\n📱 {dev}  ({len(rows)} 条)")
        if len(rows) < 2:
            print("   记录过少，跳过")
            continue

        # 上报间隔
        gaps = []
        for i in range(1, len(rows)):
            g = (rows[i]["timestamp"] - rows[i-1]["timestamp"]) / 1000.0
            if g > 0: gaps.append(g)
        if gaps:
            avg = sum(gaps) / len(gaps)
            gaps_sorted = sorted(gaps)
            med = gaps_sorted[len(gaps_sorted)//2]
            p95 = gaps_sorted[int(len(gaps_sorted)*0.95)] if len(gaps_sorted) > 1 else med
            mx = max(gaps)
            print(f"   ⏱️  上报间隔: 平均 {avg/60:.1f}分 | 中位 {med/60:.1f}分 | P95 {p95/60:.1f}分 | 最大 {mx/3600:.1f}h")
            deviation = (avg - REPORT_INTERVAL_SEC) / REPORT_INTERVAL_SEC * 100
            if abs(deviation) < 20:
                print(f"        判定: ✅ 正常（偏差 {deviation:+.0f}%）")
            elif deviation > 0:
                print(f"        判定: ⚠️ 偏慢 {deviation:+.0f}%（可能有漏报）")
            else:
                print(f"        判定: ⚠️ 偏快 {deviation:+.0f}%（可能有额外触发）")

        # 失联事件
        outages = []
        for i in range(1, len(rows)):
            g = (rows[i]["timestamp"] - rows[i-1]["timestamp"]) / 1000.0
            if g > STALE_THRESHOLD_SEC:
                outages.append((g/3600, rows[i-1]["timestamp"], rows[i]["timestamp"]))
        if outages:
            print(f"   🚨 失联事件: {len(outages)} 次（最长 {max(o[0] for o in outages):.1f}h）")
            for hours, s, e in sorted(outages, reverse=True)[:3]:
                s_str = datetime.fromtimestamp(s/1000).strftime('%m-%d %H:%M')
                e_str = datetime.fromtimestamp(e/1000).strftime('%m-%d %H:%M')
                print(f"      {s_str} → {e_str} ({hours:.1f}h)")
        else:
            print(f"   ✅ 无失联事件")

        # 上报成功率（基于天级期望值）
        daily_counts = Counter(r["day_str"] for r in rows if r["day_str"])
        expected = 24 * 60 / (REPORT_INTERVAL_SEC / 60)
        low_days = sum(1 for d, cnt in daily_counts.items() if cnt < expected * 0.5)
        if low_days > 0:
            print(f"   ⚠️ {low_days} 天上报数 < 期望值的 50%（期望 {expected:.0f} 次/天）")

        # 坐标跳变
        jumps = []
        for i in range(1, len(rows)):
            if rows[i]["lat"] is None or rows[i-1]["lat"] is None: continue
            dist = haversine_km(rows[i-1]["lat"], rows[i-1]["lng"], rows[i]["lat"], rows[i]["lng"])
            gap_h = (rows[i]["timestamp"] - rows[i-1]["timestamp"]) / 3600000.0
            if dist > 5 and gap_h < 1:
                jumps.append((dist, gap_h, rows[i]["timestamp"]))
        if jumps:
            print(f"   📍 坐标异常跳变（1h 内 >5km）: {len(jumps)} 次")
        else:
            print(f"   ✅ 无异常坐标跳变")

        # 网络类型分布
        net_counter = Counter(r["network_type"] for r in rows if r["network_type"])
        if net_counter:
            parts = [f"{k}={v}" for k, v in net_counter.most_common()]
            print(f"   📶 网络分布: {' | '.join(parts)}")


def analyze_hourly(c, device):
    section("🕐 24 小时热力图")
    if device:
        rows = c.execute("SELECT hour, weekday, COUNT(*) FROM reports WHERE device_id=? GROUP BY hour, weekday", (device,)).fetchall()
    else:
        rows = c.execute("SELECT hour, weekday, COUNT(*) FROM reports GROUP BY hour, weekday").fetchall()

    grid = [[0] * 24 for _ in range(7)]
    max_cnt = 1
    for r in rows:
        h, wd, cnt = r[0], r[1], r[2]
        if h is None or wd is None: continue
        if 0 <= h < 24 and 0 <= wd < 7:
            grid[wd][h] = cnt
            if cnt > max_cnt: max_cnt = cnt

    wd_names = ["一", "二", "三", "四", "五", "六", "日"]
    print("\n    ", end="")
    for h in range(24):
        print(f"{h:>3}", end="")
    print()
    for wd in range(7):
        print(f"周{wd_names[wd]}", end="")
        for h in range(24):
            v = grid[wd][h]
            if v == 0:
                print("  .", end="")
            elif v < max_cnt * 0.33:
                print("  ░", end="")
            elif v < max_cnt * 0.66:
                print("  ▒", end="")
            else:
                print("  █", end="")
        print()


def analyze_commute(c, device, days):
    section(f"🏠 上下班推断（最近 {days} 天）")
    if not device:
        row = c.execute("SELECT device_id FROM reports WHERE lat IS NOT NULL GROUP BY device_id ORDER BY COUNT(*) DESC LIMIT 1").fetchone()
        if not row: print("⚠️ 无定位数据"); return
        device = row[0]
        print(f"   （自动选择数据量最大的设备: {device}）")

    start = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")
    rows = c.execute("""
        SELECT timestamp, lat, lng, day_str, hour, weekday
        FROM reports WHERE device_id=? AND day_str>=? AND lat IS NOT NULL AND lng IS NOT NULL
        ORDER BY timestamp
    """, (device, start)).fetchall()
    if not rows: print("⚠️ 无有效定位"); return

    # 网格聚类（0.005 度 ≈ 500 米），找夜间在最多的为"家"
    night_hours = set(range(0, 6)) | set(range(22, 24))
    grid = defaultdict(list)
    for r in rows:
        key = (round(r["lat"] / 0.005), round(r["lng"] / 0.005))
        grid[key].append(r)
    home_scores = {k: sum(1 for i in v if i["hour"] in night_hours) for k, v in grid.items()}
    if not home_scores: print("⚠️ 无夜间数据"); return
    home_key = max(home_scores, key=home_scores.get)
    home_lat = sum(i["lat"] for i in grid[home_key]) / len(grid[home_key])
    home_lng = sum(i["lng"] for i in grid[home_key]) / len(grid[home_key])
    print(f"\n🏠 推断家庭位置: {home_lat:.5f}, {home_lng:.5f}  ({len(grid[home_key])} 次访问)")

    # 公司：工作日 9-18 点出现最多的非家簇
    work_hours = set(range(9, 18))
    work_scores = {}
    for k, v in grid.items():
        if k == home_key: continue
        work_scores[k] = sum(1 for i in v if i["hour"] in work_hours and i["weekday"] < 5)
    if work_scores:
        work_key = max(work_scores, key=work_scores.get)
        if work_scores[work_key] > 5:
            work_lat = sum(i["lat"] for i in grid[work_key]) / len(grid[work_key])
            work_lng = sum(i["lng"] for i in grid[work_key]) / len(grid[work_key])
            print(f"🏢 推断公司位置: {work_lat:.5f}, {work_lng:.5f}  ({len(grid[work_key])} 次访问)")

    # 按天算离家/回家时间（工作日）
    by_day = defaultdict(list)
    for r in rows: by_day[r["day_str"]].append(r)

    print(f"\n{'日期':<12}{'周':<4}{'离家':>8}{'到家':>8}{'在外':>8}")
    print("-" * 44)
    for day in sorted(by_day.keys()):
        items = sorted(by_day[day], key=lambda x: x["timestamp"])
        if not items: continue
        wd = items[0]["weekday"]
        if wd >= 5: continue  # 周末不算通勤
        leave_t = None; home_t = None; was_home = True
        for i in items:
            d = haversine_km(i["lat"], i["lng"], home_lat, home_lng)
            at_home = d < 0.5
            if was_home and not at_home and leave_t is None:
                leave_t = i["timestamp"]
            if not was_home and at_home and leave_t is not None and home_t is None:
                home_t = i["timestamp"]
            was_home = at_home
        lt = datetime.fromtimestamp(leave_t/1000).strftime("%H:%M") if leave_t else "-"
        ht = datetime.fromtimestamp(home_t/1000).strftime("%H:%M") if home_t else "-"
        dur = f"{(home_t-leave_t)/3600000:.1f}h" if leave_t and home_t else "-"
        wd_names = ["一","二","三","四","五","六","日"]
        print(f"{day:<12}周{wd_names[wd]:<3}{lt:>8}{ht:>8}{dur:>8}")


def analyze_fields(c, device, days, fields_str):
    section(f"🔍 字段分布: {fields_str}")
    fields = [f.strip() for f in fields_str.split(",")]
    available = get_available_columns(c)
    start = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")

    for field in fields:
        if field not in available:
            print(f"\n⚠️ {field}: 字段不存在")
            continue
        where = f"WHERE day_str>=? AND {field} IS NOT NULL"
        args = [start]
        if device:
            where += " AND device_id=?"
            args.append(device)
        rows = c.execute(f"SELECT {field}, COUNT(*) FROM reports {where} GROUP BY {field} ORDER BY COUNT(*) DESC LIMIT 10", args).fetchall()
        if not rows:
            print(f"\n📊 {field}: 无数据")
            continue
        print(f"\n📊 {field}:")
        for r in rows:
            print(f"   {r[0]!s:<30} {r[1]:>8} 次")


def cmd_export(c, device, days, out_file):
    section(f"📤 导出 CSV（最近 {days} 天）")
    start = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")
    where = "WHERE day_str>=?"
    args = [start]
    if device:
        where += " AND device_id=?"
        args.append(device)

    cols = [r[1] for r in c.execute("PRAGMA table_info(reports)").fetchall()]
    # 剔除超大字段
    exclude = {"raw_json", "nearby_wifi_json", "ext_json", "recent_locations_json"}
    cols = [col for col in cols if col not in exclude]

    cur = c.execute(f"SELECT {','.join(cols)} FROM reports {where} ORDER BY device_id, timestamp", args)
    count = 0
    with open(out_file, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for row in cur:
            w.writerow(row)
            count += 1
    print(f"✅ 已导出 {count} 行 → {os.path.abspath(out_file)}")


# ==================== 主入口 ====================

def main():
    args = parse_args()
    c = conn()
    if not check_reports_table(c):
        print("❌ reports 表不存在，请先更新 server2.py 并运行一次")
        c.close(); sys.exit(1)

    devices = [r[0] for r in c.execute("SELECT DISTINCT device_id FROM reports").fetchall()]
    print(f"\n📊 数据库: {os.path.abspath(DB_FILE)}")
    print(f"   设备: {', '.join(devices) or '无'}")
    print(f"   分析范围: 最近 {args['days']} 天")
    if args["device"]:
        print(f"   目标设备: {args['device']}")

    if args["export"]:
        cmd_export(c, args["device"], args["days"], args["export"])
        c.close()
        return

    devices_to_analyze = [args["device"]] if args["device"] else devices
    if not devices_to_analyze:
        print("⚠️ 无数据")
        c.close(); return

    # 默认输出每日汇总
    if not (args["health"] or args["hourly"] or args["commute"] or args["fields"]):
        for dev in devices_to_analyze:
            analyze_daily(c, dev, args["days"])
    else:
        for dev in devices_to_analyze:
            if args["health"]: analyze_health(c, dev, args["days"])
            if args["hourly"]: analyze_hourly(c, dev)
            if args["commute"]: analyze_commute(c, dev, args["days"])
            if args["fields"]: analyze_fields(c, dev, args["days"], args["fields"])

    c.close()
    print(f"\n{'=' * 62}\n✅ 分析完成\n{'=' * 62}")


if __name__ == "__main__":
    main()
