"""Local speech recognition with faster-whisper (runs entirely on this computer)."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass

from .text import words

CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "audiread-sync")


@dataclass
class Word:
    text: str
    file: int
    ms: int  # position within that file


def audio_duration_ms(path: str) -> int:
    import av  # installed with faster-whisper

    with av.open(path) as c:
        if c.duration:
            return int(c.duration / 1000)  # av.time_base is microseconds
        s = c.streams.audio[0]
        if s.duration and s.time_base:
            return int(s.duration * s.time_base * 1000)
    return 0


def _cache_path(path: str, model: str, language: str | None) -> str:
    st = os.stat(path)
    key = f"{os.path.abspath(path)}|{st.st_size}|{int(st.st_mtime)}|{model}|{language or 'auto'}|v1"
    return os.path.join(CACHE_DIR, hashlib.sha1(key.encode()).hexdigest() + ".json")


class Transcriber:
    def __init__(self, model: str = "base.en", language: str | None = None, device: str = "auto", log=print):
        self.model_name = model
        self.language = language if not model.endswith(".en") else "en"
        self.device = device
        self.log = log
        self._model = None

    def _load(self):
        if self._model is None:
            from faster_whisper import WhisperModel

            device = self.device
            compute = "int8"
            if device == "auto":
                try:
                    import ctranslate2

                    device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
                except Exception:
                    device = "cpu"
            if device == "cuda":
                compute = "float16"
            self.log(f"Loading speech model '{self.model_name}' on {device.upper()} (first run downloads it)…")
            self._model = WhisperModel(
                self.model_name, device=device, compute_type=compute,
                cpu_threads=max(1, (os.cpu_count() or 4)),
            )
        return self._model

    def transcribe(self, path: str, file_index: int, progress=None, should_stop=lambda: False) -> list[Word]:
        """Returns the words spoken in one audio file with their times. Results are cached."""
        cache = _cache_path(path, self.model_name, self.language)
        if os.path.exists(cache):
            try:
                with open(cache) as f:
                    segs = json.load(f)
                if progress:
                    progress(1.0)
                return _segments_to_words(segs, file_index)
            except Exception:
                pass
        model = self._load()
        segments, info = model.transcribe(
            path,
            language=self.language,
            beam_size=1,
            best_of=1,
            temperature=0.0,
            condition_on_previous_text=False,
            vad_filter=True,
            without_timestamps=False,
        )
        dur = max(info.duration, 1.0)
        segs = []
        for s in segments:
            if should_stop():
                raise InterruptedError()
            segs.append([round(s.start, 2), round(s.end, 2), s.text])
            if progress:
                progress(min(1.0, s.end / dur))
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(cache, "w") as f:
            json.dump(segs, f)
        return _segments_to_words(segs, file_index)


def _segments_to_words(segs, file_index: int) -> list[Word]:
    out: list[Word] = []
    for start, end, text in segs:
        w = words(text)
        if not w:
            continue
        span = max(0.0, end - start)
        for k, t in enumerate(w):
            # spread a segment's words evenly over its duration
            ms = int((start + span * k / len(w)) * 1000)
            out.append(Word(t, file_index, ms))
    return out
