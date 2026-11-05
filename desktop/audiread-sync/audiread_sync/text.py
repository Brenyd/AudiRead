"""Word normalisation shared with the Android app.

Both sides split text into runs of Unicode letters and digits, lower-cased.
The app uses the Kotlin regex [\\p{L}\\p{N}]+ — keep the two in step.
"""

import re
from functools import cmp_to_key

_WORD = re.compile(r"[^\W_]+")


def words(text: str) -> list[str]:
    return _WORD.findall(text.lower())


def natural_key(s: str):
    return [int(p) if p.isdigit() else p.lower() for p in re.split(r"(\d+)", s)]


def natural_sorted(items, key=lambda x: x):
    return sorted(items, key=lambda x: natural_key(key(x)))
