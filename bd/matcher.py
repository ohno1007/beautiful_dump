"""Cross-version matcher.

Given signatures from two dumps of the same game (with different
randomised names), produce a name mapping.  Strategy:

  1. Build a stable-id pool from version A using anchors (strings,
     UnityEngine callees, unique prologue hashes).
  2. For each method in B, score all candidates in A; emit the highest.
  3. Propagate names via call graph closure when caller/callee structure
     matches.

The output mapping is the foundation for a "sustainable SDK" - a method
that gets randomised every build still has a stable identifier as long
as one anchor survives the rebuild.
"""
from __future__ import annotations

import json
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .signature import MethodSig


# Feature weights tuned for IL2CPP randomisers - strings dominate because
# embedded literals are by far the most reliable cross-build anchor.
W_STRING_XREF = 6.0
W_STABLE_CALLEE = 3.0
W_PROLOGUE = 2.0
W_RETURN_TYPE = 1.0
W_PARAM_TYPES = 1.5
W_PARAM_COUNT = 0.5
W_CLASS_FAMILY = 0.7


@dataclass
class Match:
    src_addr: int          # addr in version A
    src_name: str
    dst_addr: int          # addr in version B
    dst_name: str
    score: float
    reasons: list[str]


def _set_overlap(a: list[str], b: list[str]) -> float:
    if not a and not b:
        return 0.0
    sa, sb = set(a), set(b)
    inter = len(sa & sb)
    if inter == 0:
        return 0.0
    return inter / max(len(sa | sb), 1)


def _score(a: MethodSig, b: MethodSig) -> tuple[float, list[str]]:
    s = 0.0
    why: list[str] = []

    str_iou = _set_overlap(a.string_xrefs, b.string_xrefs)
    if str_iou:
        s += W_STRING_XREF * str_iou
        why.append(f"strings={str_iou:.2f}({len(set(a.string_xrefs) & set(b.string_xrefs))})")

    call_iou = _set_overlap(a.stable_callees, b.stable_callees)
    if call_iou:
        s += W_STABLE_CALLEE * call_iou
        why.append(f"callees={call_iou:.2f}")

    if a.native_prologue_hash and a.native_prologue_hash == b.native_prologue_hash:
        s += W_PROLOGUE
        why.append("prologue=eq")

    if a.return_type and a.return_type == b.return_type:
        s += W_RETURN_TYPE
        why.append("ret=eq")

    if a.param_types == b.param_types and a.param_types:
        s += W_PARAM_TYPES
        why.append("params=eq")
    elif len(a.param_types) == len(b.param_types):
        s += W_PARAM_COUNT
        why.append("paramcount=eq")

    # If class is in a stable namespace, that's another anchor
    if a.class_name and a.class_name == b.class_name:
        s += W_CLASS_FAMILY
        why.append("class=eq")

    return s, why


def _bucket(sigs: list[MethodSig]) -> dict[tuple[int, str], list[MethodSig]]:
    """Bucket by (param_count, return_type) to avoid O(N^2) pairwise."""
    out: dict[tuple[int, str], list[MethodSig]] = defaultdict(list)
    for s in sigs:
        out[(s.param_count, s.return_type)].append(s)
    return out


def match(
    src: list[MethodSig],
    dst: list[MethodSig],
    min_score: float = 3.0,
    accept_score: float = 5.0,
) -> list[Match]:
    """Return matches src -> dst with score >= min_score.

    Ambiguous matches (multiple candidates within 0.5 of the top score)
    are dropped unless the top exceeds `accept_score`.
    """
    dst_buckets = _bucket(dst)
    results: list[Match] = []

    for a in src:
        # Candidate set: same bucket + neighbouring param counts
        cands: list[MethodSig] = []
        for delta in (0, -1, 1):
            cands.extend(dst_buckets.get((a.param_count + delta, a.return_type), []))
        # Also widen on return type for void overloads with unknown returns
        if a.return_type in ("?", "System.Void", ""):
            for pc in (a.param_count - 1, a.param_count, a.param_count + 1):
                for (cpc, _ret), arr in dst_buckets.items():
                    if cpc == pc:
                        cands.extend(arr)

        scored: list[tuple[float, list[str], MethodSig]] = []
        for b in cands:
            sc, why = _score(a, b)
            if sc >= min_score:
                scored.append((sc, why, b))

        if not scored:
            continue
        scored.sort(key=lambda x: x[0], reverse=True)
        best_sc, best_why, best = scored[0]
        if len(scored) > 1 and scored[1][0] >= best_sc - 0.5 and best_sc < accept_score:
            continue
        results.append(Match(
            src_addr=a.addr, src_name=f"{a.class_name}::{a.name}",
            dst_addr=best.addr, dst_name=f"{best.class_name}::{best.name}",
            score=best_sc, reasons=best_why,
        ))

    return results


def save_mapping(matches: list[Match], out: Path) -> None:
    out.write_text(json.dumps([m.__dict__ for m in matches], indent=2, ensure_ascii=False))


def stats(matches: list[Match]) -> dict:
    if not matches:
        return {"count": 0}
    scores = [m.score for m in matches]
    return {
        "count": len(matches),
        "min_score": min(scores),
        "max_score": max(scores),
        "avg_score": sum(scores) / len(scores),
        "high_conf": sum(1 for s in scores if s >= 7),
    }
