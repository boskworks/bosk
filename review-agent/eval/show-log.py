#!/usr/bin/env python3
"""Render an opencode generation log as readable activity for monitoring.

Generation logs under data/eval/<prompt-hash>-<model>/<PR>.log are JSON-lines.
This prints each step's text (and, with --tools, its tool calls) so a running
generation can be watched: `show-log.py --follow data/eval/.../439.log`.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)


def render_line(line: str, show_tools: bool) -> str | None:
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return line.rstrip()
    part = obj.get("part", {})
    if obj.get("type") == "text" and part.get("text"):
        return part["text"].rstrip()
    if show_tools and obj.get("type") in ("tool_use", "tool_call"):
        return f"[tool] {part.get('tool', part.get('name', '?'))} {json.dumps(part.get('args', part.get('input', {})))[:200]}"
    return None


def main():
    parser = argparse.ArgumentParser(description="Render an opencode generation log as readable activity.")
    parser.add_argument("log", type=Path)
    parser.add_argument("--tools", action="store_true", help="also show tool calls")
    parser.add_argument("--follow", action="store_true", help="keep reading new lines until interrupted")
    args = parser.parse_args()

    if args.follow:
        with args.log.open() as f:
            f.seek(0, 2)
            while True:
                line = f.readline()
                if line:
                    text = render_line(line, args.tools)
                    if text:
                        print(text)
                else:
                    time.sleep(1)
    else:
        for line in args.log.read_text().splitlines():
            text = render_line(line, args.tools)
            if text:
                print(text)


if __name__ == "__main__":
    main()
