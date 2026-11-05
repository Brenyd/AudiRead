"""Command line: audiread-sync --ebook book.epub --audio folder/ [--out file.json]"""

from __future__ import annotations

import argparse
import sys

from .sync import build, default_output


def main(argv=None):
    ap = argparse.ArgumentParser(prog="audiread-sync", description="Line up an audiobook with its ebook for AudiRead.")
    ap.add_argument("--ebook", help="EPUB, MOBI/AZW3, FB2, TXT or HTML file")
    ap.add_argument("--audio", nargs="+", help="Audiobook files or a folder")
    ap.add_argument("--out", help="Where to save the .audiread-sync.json (default: next to the ebook)")
    ap.add_argument("--model", default="base.en",
                    help="Speech model: tiny.en, base.en (default), small.en, or tiny/base/small/medium for other languages")
    ap.add_argument("--language", default=None, help="Language code for multilingual models, e.g. de, fr")
    ap.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    ap.add_argument("--gui", action="store_true", help="Open the window (default when no --ebook is given)")
    a = ap.parse_args(argv)

    if a.gui or not a.ebook:
        from .gui import run
        run()
        return 0
    if not a.audio:
        ap.error("--audio is required")

    last = {"stage": None, "pct": -1}

    def progress(stage, frac):
        pct = int(frac * 100)
        if stage != last["stage"] or pct != last["pct"]:
            last.update(stage=stage, pct=pct)
            label = "Listening" if stage == "transcribe" else "Matching"
            print(f"\r{label}: {pct:3d}%", end="", file=sys.stderr, flush=True)

    def log(msg):
        print("\r" + msg + " " * 10, file=sys.stderr)

    r = build(a.ebook, a.audio, a.out or default_output(a.ebook), model=a.model, language=a.language,
              device=a.device, log=log, progress=progress)
    print(f"\nDone in {r.seconds / 60:.1f} min — {r.anchors:,} sync points, {r.coverage:.0%} of the book covered.",
          file=sys.stderr)
    print(r.path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
