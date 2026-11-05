"""Extracts the reading-order text of an ebook as a list of normalised words."""

from __future__ import annotations

import os
import posixpath
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile
from html.parser import HTMLParser

from .text import words


class _TextExtractor(HTMLParser):
    """Collects visible body text, skipping <head>, scripts and styles."""

    SKIP = {"head", "script", "style", "title", "svg"}
    BLOCK = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "blockquote", "section", "hr"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        t = tag.lower().split(":")[-1]
        if t in self.SKIP:
            self.skip += 1
        elif t in self.BLOCK:
            self.parts.append(" ")

    def handle_endtag(self, tag):
        t = tag.lower().split(":")[-1]
        if t in self.SKIP and self.skip > 0:
            self.skip -= 1
        elif t in self.BLOCK:
            self.parts.append(" ")

    def handle_startendtag(self, tag, attrs):
        if tag.lower().split(":")[-1] in self.BLOCK:
            self.parts.append(" ")

    def handle_data(self, data):
        if self.skip == 0:
            self.parts.append(data)


def html_text(markup: str) -> str:
    p = _TextExtractor()
    p.feed(markup)
    p.close()
    return "".join(p.parts)


def _decode(b: bytes) -> str:
    for enc in ("utf-8-sig", "utf-16"):
        try:
            if enc == "utf-16" and not (b[:2] in (b"\xff\xfe", b"\xfe\xff")):
                continue
            return b.decode(enc)
        except UnicodeDecodeError:
            pass
    return b.decode("cp1252", errors="replace")


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].split(":")[-1].lower()


def epub_text(path: str) -> str:
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        opf_path = None
        if "META-INF/container.xml" in names:
            root = ET.fromstring(z.read("META-INF/container.xml"))
            for el in root.iter():
                if _local(el.tag) == "rootfile" and el.get("full-path"):
                    opf_path = el.get("full-path")
                    break
        if not opf_path or opf_path not in names:
            opf_path = next((n for n in names if n.lower().endswith(".opf")), None)
        if not opf_path:
            raise ValueError("Not a valid EPUB (no package file)")
        base = posixpath.dirname(opf_path)
        opf = ET.fromstring(z.read(opf_path))
        manifest = {}
        spine = []
        for el in opf.iter():
            t = _local(el.tag)
            if t == "item" and el.get("id"):
                manifest[el.get("id")] = el.get("href", "")
            elif t == "itemref" and el.get("idref"):
                spine.append(el.get("idref"))
        out = []
        seen = set()
        for idref in spine:
            href = manifest.get(idref)
            if not href:
                continue
            p = posixpath.normpath(posixpath.join(base, urllib.parse.unquote(href.split("#")[0])))
            if p in seen or p not in names:
                continue
            seen.add(p)
            out.append(html_text(_decode(z.read(p))))
        return "\n".join(out)


def fb2_text(data: bytes) -> str:
    root = ET.fromstring(data)
    out = []
    for body in root:
        if _local(body.tag) != "body":
            continue
        out.append(" ".join(t for t in body.itertext()))
    return "\n".join(out)


def _calibre_txt(path: str) -> str | None:
    exe = shutil.which("ebook-convert")
    if not exe:
        return None
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "book.txt")
        subprocess.run([exe, path, out], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(out, "rb") as f:
            return _decode(f.read())


def _mobi_text(path: str) -> str:
    try:
        import mobi  # type: ignore
    except ImportError as e:
        raise ValueError("Reading MOBI/AZW3 needs the 'mobi' package or Calibre installed") from e
    tmpdir, filepath = mobi.extract(path)
    try:
        if filepath.lower().endswith(".epub"):
            return epub_text(filepath)
        with open(filepath, "rb") as f:
            return html_text(_decode(f.read()))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


SUPPORTED = (".epub", ".kepub", ".txt", ".md", ".markdown", ".html", ".htm", ".xhtml", ".fb2",
             ".fb2.zip", ".mobi", ".azw", ".azw3", ".prc")


def read_words(path: str) -> list[str]:
    """Returns the book's words in reading order."""
    low = path.lower()
    if low.endswith((".epub", ".kepub")):
        text = epub_text(path)
    elif low.endswith((".txt", ".md", ".markdown")):
        with open(path, "rb") as f:
            text = _decode(f.read())
    elif low.endswith((".html", ".htm", ".xhtml")):
        with open(path, "rb") as f:
            text = html_text(_decode(f.read()))
    elif low.endswith(".fb2"):
        with open(path, "rb") as f:
            text = fb2_text(f.read())
    elif low.endswith(".fb2.zip"):
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.lower().endswith(".fb2"))
            text = fb2_text(z.read(name))
    elif low.endswith((".mobi", ".azw", ".azw3", ".prc")):
        text = _calibre_txt(path) or _mobi_text(path)
    else:
        text = _calibre_txt(path)
        if text is None:
            raise ValueError(f"Unsupported ebook type: {os.path.basename(path)}")
    w = words(re.sub(r"\s+", " ", text))
    if len(w) < 50:
        raise ValueError("Couldn't find any text in this ebook (is it a scanned PDF or a comic?)")
    return w
