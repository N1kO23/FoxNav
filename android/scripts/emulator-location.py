#!/usr/bin/env python3
"""Feeds locations to a running Android emulator for testing FoxNav.

    android/scripts/emulator-location.py set 60.1699 24.9384        # stand still in Helsinki
    android/scripts/emulator-location.py replay track.gpx [--speedup 2]   # drive a recorded track

Newer emulator images ignore `adb emu geo fix`, so this uses shell test providers (the emulator's
shell user gets the mock-location permission; nothing changes on real phones): GPS for FoxNav,
and fused and network for apps that ask Play services, like Chrome for the web convoy page.
Speed isn't reported this way; FoxNav derives it from consecutive fixes.
"""
import argparse
import datetime
import subprocess
import time
import xml.etree.ElementTree as ElementTree


def adb(*args: str) -> str:
    return subprocess.run(["adb", "shell", *args], check=True, capture_output=True, text=True).stdout


PROVIDERS = ("gps", "fused", "network")


def prepare() -> None:
    adb("appops", "set", "com.android.shell", "android:mock_location", "allow")
    for provider in PROVIDERS:
        adb("cmd", "location", "providers", "add-test-provider", provider)
        adb("cmd", "location", "providers", "set-test-provider-enabled", provider, "true")


def send(latitude: float, longitude: float) -> None:
    for provider in PROVIDERS:
        adb("cmd", "location", "providers", "set-test-provider-location", provider, "--location", f"{latitude},{longitude}", "--accuracy", "5")


def track_points(path: str) -> list[tuple[float, float, datetime.datetime | None]]:
    namespace = {"gpx": "http://www.topografix.com/GPX/1/1"}
    points = []
    for point in ElementTree.parse(path).getroot().iterfind(".//gpx:trkpt", namespace):
        when = point.find("gpx:time", namespace)
        timestamp = datetime.datetime.fromisoformat(when.text.replace("Z", "+00:00")) if when is not None else None
        points.append((float(point.get("lat")), float(point.get("lon")), timestamp))
    return points


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    set_command = commands.add_parser("set", help="set a fixed location")
    set_command.add_argument("latitude", type=float)
    set_command.add_argument("longitude", type=float)
    replay = commands.add_parser("replay", help="replay a GPX track in real time (or faster)")
    replay.add_argument("gpx")
    replay.add_argument("--speedup", type=float, default=1.0)
    args = parser.parse_args()

    prepare()
    if args.command == "set":
        send(args.latitude, args.longitude)
        return

    points = track_points(args.gpx)
    print(f"Replaying {len(points)} points from {args.gpx}")
    for index, (latitude, longitude, timestamp) in enumerate(points):
        send(latitude, longitude)
        if index + 1 < len(points):
            following = points[index + 1][2]
            gap = (following - timestamp).total_seconds() if following and timestamp else 1.0
            time.sleep(max(gap, 0.1) / args.speedup)


if __name__ == "__main__":
    main()
