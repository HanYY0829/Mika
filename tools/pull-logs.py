#!/usr/bin/env python3
"""Export Mika's run records and detailed traces through an authorized ADB connection."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--device", required=True)
    parser.add_argument("--package", default="com.wangzan101.mika.logs")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("Invalid Android package name")
    # shell -T preserves binary output and reports the remote exit status;
    # exec-out can report success even when run-as failed on the device.
    adb = [args.adb, "-s", args.device, "shell", "-T", "run-as", args.package]
    # Fail before creating an output if Android does not allow run-as for this package.
    subprocess.run(adb + ["pwd"], check=True, stdout=subprocess.DEVNULL)
    paths = []
    for path in ("files/runs", "no_backup/harness-traces"):
        exists = subprocess.run(adb + ["test", "-d", path], capture_output=True)
        if exists.returncode == 0:
            paths.append(path)
        elif exists.returncode != 1:
            raise SystemExit(exists.stderr.decode(errors="replace"))
    if not paths:
        raise SystemExit("No records yet. Run a task in Mika Logs first.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as dest:
            temporary = Path(dest.name)
            subprocess.run(adb + ["tar", "-cf", "-", *paths], stdout=dest, check=True)
        temporary.replace(args.output)
        print(args.output.resolve())
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
