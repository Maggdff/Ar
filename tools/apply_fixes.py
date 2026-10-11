#!/usr/bin/env python3
"""يطبّق tools/content_fixes.json على بيانات lessons/questions (في الذاكرة أو على ملفات assets)."""
import json, pathlib, re
HERE = pathlib.Path(__file__).resolve().parent
ASSETS = HERE.parent / "app/src/main/assets"

def apply(lessons, questions):
    fx = json.loads((HERE / "content_fixes.json").read_text(encoding="utf-8"))
    L = {l["id"]: l for l in lessons}; Q = {q["id"]: q for q in questions}
    for f in fx["lessons"]:
        l = L[f["id"]]; cur = l[f["field"]]
        if isinstance(cur, list):
            hits = [i for i, s in enumerate(cur) if f["match"] in s]
            if not hits and any(f["new"] == s for s in cur): continue   # مطبّق سابقًا
            assert len(hits) == 1, (f["id"], f["field"], "match", hits)
            if f["new"]: cur[hits[0]] = f["new"]
            else: del cur[hits[0]]
        else:
            l[f["field"]] = f["new"]
    for f in fx["questions"]:
        Q[f["id"]][f["field"]] = f["new"]

if __name__ == "__main__":
    lp, qp = ASSETS / "lessons.json", ASSETS / "questions.json"
    ld = json.loads(lp.read_text(encoding="utf-8")); qd = json.loads(qp.read_text(encoding="utf-8"))
    apply(ld["lessons"], qd)
    lp.write_text(json.dumps(ld, ensure_ascii=False, indent=1), encoding="utf-8")
    qp.write_text(json.dumps(qd, ensure_ascii=False, indent=1), encoding="utf-8")
    print("fixes applied")
