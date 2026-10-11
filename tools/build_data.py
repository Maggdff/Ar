#!/usr/bin/env python3
"""Builds app/src/main/assets/*.json from the six book-analysis files.
Nothing is invented: every field comes from the source files, except the four
documented question overrides in tools/overrides.json (listed in DATA_AUDIT.md)."""
import json, re, sys, pathlib

SRC = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/mnt/user-data/uploads")
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets"
OUT.mkdir(parents=True, exist_ok=True)
load = lambda n: json.loads((SRC / n).read_text(encoding="utf-8"))

index = load("book_index.json")
content = load("book_content.json")
verified = load("book_content_verified.json")
qbank = load("question_bank.json")
corr = load("corrections_log.json")
overrides = json.loads((pathlib.Path(__file__).parent / "overrides.json").read_text(encoding="utf-8"))

raw = {l["lesson_id"]: l for l in content["lessons"]}
ver = {l["lesson_id"]: l for l in verified["lessons"]}

units, lessons = [], []
for u in index["units"]:
    units.append({"id": u["unit_id"], "title": u["unit_title_original"],
                  "lessonIds": [l["lesson_id"] for l in u["lessons"]]})
    for l in u["lessons"]:
        lid = l["lesson_id"]
        v, r = ver[lid], raw[lid]
        lessons.append({
            "id": lid, "unitId": u["unit_id"], "title": l["title"],
            "position": l["position_ref"],
            "ruleText": v["original_rule_text"],
            "conditions": v.get("conditions_exceptions", []),
            "examples": [{"text": e["example"], "note": e["explanation_of_citation"]} for e in v.get("original_examples", [])],
            "summary": v["summary"],
            "differences": v.get("differences_with_similar_rules", ""),
            "commonErrors": v.get("common_errors_and_notes", ""),
            "verificationStatus": v["verification_status"],
            "verificationNotes": v.get("verification_notes", ""),
            "excerpt": r["original_excerpt"],
            "extractionNote": r.get("extraction_notes", ""),
        })

def ids_of(s):  # lesson ids mentioned in a free-text reference
    return sorted(set(re.findall(r"U\d-L\d\d", s or "")))

questions = []
for q in qbank["questions"]:
    q = dict(q)
    note = ""
    if q["question_id"] in overrides:
        o = overrides[q["question_id"]]
        for k in ("correct_answer", "explanation"):
            q[k] = o[k]
        note = o["reason"]
    questions.append({
        "id": q["question_id"], "text": q["question_text"], "type": q["question_type"],
        "options": q["options"] or [], "answer": q["correct_answer"],
        "explanation": q["explanation"], "correctionIfFalse": q.get("correction_if_false") or "",
        "unitIds": sorted(set(re.findall(r"U\d", q["unit_id"]))),
        "lessonIds": ids_of(q["lesson_id"]),
        "difficulty": q["difficulty"], "sourceType": q["source_type"],
        "verification": q["verification_status"], "appNote": note,
    })

corrections = []
for c in corr["corrections"]:
    corrections.append({
        "id": c["correction_id"], "category": c["category"], "lessonRef": c["lesson_id"],
        "lessonIds": ids_of(c["lesson_id"]), "original": c["original_text"],
        "suggested": c["suggested_correction"], "reason": c["reason"],
        "confidence": c["confidence"], "status": c["review_status"], "notes": c["notes"],
    })

meta = {
    "title": "موجز في بعض مهارات اللغة العربية",
    "isCompleteBook": index["meta"]["is_complete_book"],
    "completenessNote": index["meta"]["completeness_note"],
    "extractionStatus": index["meta"]["extraction_status"],
}

def dump(name, obj):
    (OUT / name).write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")

import apply_fixes; apply_fixes.apply(lessons, questions)
dump("lessons.json", {"units": units, "lessons": lessons})
dump("questions.json", questions)
dump("corrections.json", corrections)
dump("meta.json", meta)
print(f"units={len(units)} lessons={len(lessons)} questions={len(questions)} corrections={len(corrections)}")
