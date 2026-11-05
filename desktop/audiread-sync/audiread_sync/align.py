"""Aligns a speech transcript with the book text.

Both are sequences of normalised words. Matching works like a diff tool for very long texts:

1. Find n-grams that occur exactly once in the transcript and exactly once in the book.
2. Keep the largest set of those matches that is in the same order in both (longest increasing
   subsequence) — this throws away chance matches, front matter, credits and recognition errors.
3. Repeat inside every gap between two kept matches with shorter n-grams, which are unique within
   the smaller gap even when they aren't unique in the whole book.

The result is a dense list of (transcript word, book word) pairs in order.
"""

from __future__ import annotations

from bisect import bisect_left
from collections import Counter


def _unique_ngrams(seq, lo, hi, n):
    counts = Counter(tuple(seq[i:i + n]) for i in range(lo, hi - n + 1))
    return {g for g, c in counts.items() if c == 1}


def _matches(a, alo, ahi, b, blo, bhi, n):
    if ahi - alo < n or bhi - blo < n:
        return []
    ua = _unique_ngrams(a, alo, ahi, n)
    if not ua:
        return []
    ub = _unique_ngrams(b, blo, bhi, n)
    common = ua & ub
    if not common:
        return []
    pos_b = {}
    for j in range(blo, bhi - n + 1):
        g = tuple(b[j:j + n])
        if g in common:
            pos_b[g] = j
    pairs = []
    for i in range(alo, ahi - n + 1):
        g = tuple(a[i:i + n])
        if g in common:
            pairs.append((i, pos_b[g]))
    return pairs


def _lis(pairs):
    """Longest subsequence of pairs (already sorted by i) that is strictly increasing in j."""
    if not pairs:
        return []
    tails = []      # smallest tail j for each length
    tails_idx = []  # index into pairs of that tail
    prev = [-1] * len(pairs)
    for k, (_, j) in enumerate(pairs):
        p = bisect_left(tails, j)
        if p == len(tails):
            tails.append(j)
            tails_idx.append(k)
        else:
            tails[p] = j
            tails_idx[p] = k
        prev[k] = tails_idx[p - 1] if p > 0 else -1
    out = []
    k = tails_idx[-1]
    while k >= 0:
        out.append(pairs[k])
        k = prev[k]
    out.reverse()
    return out


def align(a: list[str], b: list[str], ngram_sizes=(6, 5, 4, 3, 2), progress=None) -> list[tuple[int, int]]:
    """Returns ordered (index in a, index in b) pairs of matching words."""
    result: list[tuple[int, int]] = []
    # work items: (alo, ahi, blo, bhi, level)
    stack = [(0, len(a), 0, len(b), 0)]
    total = max(1, len(a))
    done_words = 0
    while stack:
        alo, ahi, blo, bhi, level = stack.pop()
        if ahi <= alo or bhi <= blo:
            continue
        # Don't stretch a gap's matching across wildly different sizes (e.g. skipped chapters).
        found = []
        n_used = None
        for li in range(level, len(ngram_sizes)):
            n = ngram_sizes[li]
            # tiny n-grams are only meaningful in small gaps
            if n <= 3 and (ahi - alo > 4000 or bhi - blo > 4000):
                continue
            found = _lis(_matches(a, alo, ahi, b, blo, bhi, n))
            if found:
                n_used = li
                break
        if not found:
            done_words += ahi - alo
            continue
        n = ngram_sizes[n_used]
        # record matched words and queue the gaps
        gaps = []
        pa, pb = alo, blo
        for (i, j) in found:
            if i < pa or j < pb:
                continue
            gaps.append((pa, i, pb, j))
            for k in range(n):
                result.append((i + k, j + k))
            pa, pb = i + n, j + n
        gaps.append((pa, ahi, pb, bhi))
        for g in gaps:
            stack.append((g[0], g[1], g[2], g[3], 0 if g[1] - g[0] < (ahi - alo) else n_used + 1))
        done_words += len(found) * n
        if progress:
            progress(min(1.0, done_words / total))
    result.sort()
    # enforce strict monotonicity in both sequences
    clean = []
    last_j = -1
    for i, j in result:
        if j > last_j and (not clean or i > clean[-1][0]):
            clean.append((i, j))
            last_j = j
    return clean
