"""Verify executed runtime tests and remove credentials before artifact upload."""

import json
from pathlib import Path
import sys


def redact(value):
    if isinstance(value, dict):
        return {key: redact(item) for key, item in value.items() if key != "token"}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def verify(summary):
    if summary.get("status") != "passed" or summary.get("failed") != 0:
        raise ValueError("TeaKit did not pass")
    runs = summary.get("runs", [])
    if len(runs) != 1:
        raise ValueError("Expected one executed Minecraft node")
    run = runs[0]
    result = run.get("result", {})
    if result.get("passed", 0) < 1 or result.get("failed") != 0:
        raise ValueError("Expected executed, passing tests")
    display = run.get("backgroundDisplay", {})
    number = display.get("display", "").removeprefix(":")
    if display.get("provider") != "xvfb" or not number.isdigit():
        raise ValueError("Expected a TeaKit-owned virtual display")
    if Path(f"/tmp/.X11-unix/X{number}").exists():
        raise ValueError("The virtual display is still running")
    instance = json.loads(Path(run["artifactFiles"]["instance"]).read_text())
    pid = instance.get("pid")
    if not isinstance(pid, int) or pid <= 0 or Path(f"/proc/{pid}").exists():
        raise ValueError("Minecraft did not stop cleanly")
    print(f"{result['passed']} tests passed; Minecraft and Xvfb stopped")


def main():
    mode, filename = sys.argv[1:]
    path = Path(filename)
    if mode == "redact":
        if path.exists():
            path.write_text(json.dumps(redact(json.loads(path.read_text())), indent=2) + "\n")
    elif mode == "verify":
        verify(json.loads(path.read_text()))
    else:
        raise ValueError(f"Unknown report action: {mode}")


if __name__ == "__main__":
    main()
