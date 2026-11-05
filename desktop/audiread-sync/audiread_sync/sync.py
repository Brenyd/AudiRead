"""Builds an AudiRead sync file from an ebook and its audiobook."""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field

from . import __version__
from .align import align
from .ebook import read_words
from .text import natural_sorted
from .transcribe import Transcriber, Word, audio_duration_ms

AUDIO_EXT = (".mp3", ".m4a", ".m4b", ".aac", ".mp4", ".ogg", ".oga", ".opus", ".flac", ".wav",
             ".amr", ".awb", ".mka", ".webm", ".weba", ".3gp")

SNIPPET_WORDS = 6
MIN_GAP_MS = 4000


def collect_audio(paths: list[str]) -> list[str]:
    """Expands folders (recursively, e.g. CD1/CD2) and sorts files the way the app does."""
    out = []
    for p in paths:
        if os.path.isdir(p):
            found = []
            for root, _, files in os.walk(p):
                for f in files:
                    if f.lower().endswith(AUDIO_EXT):
                        full = os.path.join(root, f)
                        found.append((os.path.relpath(full, p), full))
            out.extend(full for _, full in natural_sorted(found, key=lambda x: x[0]))
        elif p.lower().endswith(AUDIO_EXT):
            out.append(p)
    if all(not os.path.isdir(p) for p in paths):
        out = natural_sorted(out, key=os.path.basename)
    return out


@dataclass
class Result:
    path: str
    anchors: int
    coverage: float
    matched: int
    transcript_words: int
    book_words: int
    seconds: float
    warnings: list[str] = field(default_factory=list)


def build(
    ebook: str,
    audio: list[str],
    out: str,
    model: str = "base.en",
    language: str | None = None,
    device: str = "auto",
    log=print,
    progress=lambda stage, frac: None,
    should_stop=lambda: False,
) -> Result:
    t0 = time.time()
    files = collect_audio(audio)
    if not files:
        raise ValueError("No audio files found")
    log(f"Reading ebook: {os.path.basename(ebook)}")
    book = read_words(ebook)
    log(f"  {len(book):,} words")

    durations = [audio_duration_ms(f) for f in files]
    total_ms = max(1, sum(durations))
    log(f"Audiobook: {len(files)} file(s), {total_ms / 3600000:.1f} hours")

    tr = Transcriber(model=model, language=language, device=device, log=log)
    transcript: list[Word] = []
    done_ms = 0
    for i, f in enumerate(files):
        if should_stop():
            raise InterruptedError()
        log(f"Listening to {os.path.basename(f)} ({i + 1}/{len(files)})…")
        base = done_ms

        def p(frac, base=base, d=durations[i]):
            progress("transcribe", (base + frac * d) / total_ms)

        transcript.extend(tr.transcribe(f, i, progress=p, should_stop=should_stop))
        done_ms += durations[i]
    log(f"  heard {len(transcript):,} words")
    if len(transcript) < 50:
        raise ValueError("Speech recognition found almost no words — is this the right audio?")

    log("Matching the narration to the text…")
    pairs = align([w.text for w in transcript], book, progress=lambda f: progress("align", f))
    if len(pairs) < 20:
        raise ValueError("The audio and the ebook don't seem to match. Check you picked the same book and edition.")

    # Thin out to one anchor every few seconds; prefer spots where a run of words matched.
    offsets = [0]
    for d in durations:
        offsets.append(offsets[-1] + d)
    anchors = []
    last_g = -10**9
    pair_set = set(pairs)
    for i, j in pairs:
        w = transcript[i]
        g = offsets[w.file] + w.ms
        if g - last_g < MIN_GAP_MS:
            continue
        # require the next two words to match too, so anchors sit inside solid stretches
        if (i + 1, j + 1) not in pair_set or (i + 2, j + 2) not in pair_set:
            continue
        if j + SNIPPET_WORDS > len(book):
            continue
        anchors.append({
            "f": w.file,
            "ms": w.ms,
            "w": " ".join(book[j:j + SNIPPET_WORDS]),
            "p": round(j / len(book), 5),
        })
        last_g = g

    first_j, last_j = pairs[0][1], pairs[-1][1]
    coverage = (last_j - first_j) / max(1, len(book))
    warnings = []
    if coverage < 0.6:
        warnings.append(f"Only {coverage:.0%} of the book lined up — the audio may be abridged or a different edition.")
    matched_ratio = len(pairs) / max(1, len(transcript))
    if matched_ratio < 0.35:
        warnings.append(f"Only {matched_ratio:.0%} of the spoken words matched the text; sync may be rough in places.")

    doc = {
        "format": "audiread-sync",
        "version": 1,
        "generator": f"AudiRead Sync {__version__}",
        "created": int(time.time() * 1000),
        "model": model,
        "book": {"file": os.path.basename(ebook), "words": len(book)},
        "audio": [{"name": os.path.basename(f), "durationMs": d} for f, d in zip(files, durations)],
        "anchors": anchors,
    }
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, separators=(",", ":"))
    for w in warnings:
        log("Warning: " + w)
    log(f"Saved {len(anchors):,} sync points to {out}")
    return Result(out, len(anchors), coverage, len(pairs), len(transcript), len(book), time.time() - t0, warnings)


def default_output(ebook: str) -> str:
    stem = os.path.basename(ebook)
    for ext in (".fb2.zip",) + tuple(e for e in (".epub", ".kepub", ".mobi", ".azw3", ".azw", ".prc", ".fb2",
                                                   ".txt", ".md", ".html", ".htm", ".xhtml")):
        if stem.lower().endswith(ext):
            stem = stem[: -len(ext)]
            break
    return os.path.join(os.path.dirname(os.path.abspath(ebook)), stem + ".audiread-sync.json")
