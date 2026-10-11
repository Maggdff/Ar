#!/usr/bin/env python3
"""Integrity checks on the generated assets. Exit code 1 on any failure."""
import json, pathlib, collections, sys
A = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets"
L = json.loads((A/"lessons.json").read_text(encoding="utf-8"))
Q = json.loads((A/"questions.json").read_text(encoding="utf-8"))
C = json.loads((A/"corrections.json").read_text(encoding="utf-8"))
errs = []
lids = {l["id"] for l in L["lessons"]}
uids = {u["id"] for u in L["units"]}
if len(lids) != len(L["lessons"]): errs.append("duplicate lesson ids")
qids = [q["id"] for q in Q]
if len(set(qids)) != len(qids): errs.append("duplicate question ids")
for q in Q:
    if q["type"] in ("true_false", "mcq"):
        if q["answer"] not in q["options"]: errs.append(f'{q["id"]}: answer not among options')
        if len(set(q["options"])) != len(q["options"]): errs.append(f'{q["id"]}: duplicate options')
        if q["type"] == "true_false" and q["options"] != ["صحيح", "خطأ"]: errs.append(f'{q["id"]}: odd TF options')
        if q["type"] == "mcq" and len(q["options"]) != 4: errs.append(f'{q["id"]}: mcq options != 4')
    else:
        if q["options"]: errs.append(f'{q["id"]}: open question has options')
    for l in q["lessonIds"]:
        if l not in lids: errs.append(f'{q["id"]}: unknown lesson {l}')
    for u in q["unitIds"]:
        if u not in uids: errs.append(f'{q["id"]}: unknown unit {u}')
    if not q["unitIds"]: errs.append(f'{q["id"]}: no unit')
    if not q["text"].strip() or not q["answer"].strip() or not q["explanation"].strip(): errs.append(f'{q["id"]}: empty field')
for c in C:
    for l in c["lessonIds"]:
        if l not in lids: errs.append(f'{c["id"]}: unknown lesson {l}')
for l in L["lessons"]:
    if not l["ruleText"].strip(): errs.append(f'{l["id"]}: empty rule')

# لا ملاحظات تحريرية داخلية في النصوص المعروضة للمستخدم
import re
BAD = re.compile(r"⚠|corrections_log|review_notes|COR-\d|\bU\d-L\d\d\b|المدقق|تلف النص|الاستخراج الضوئي|نص الكتاب الأصلي")
def visible_texts():
    for l in L["lessons"]:
        for k in ("title","ruleText","summary","differences","commonErrors"): yield l["id"], k, l[k]
        for t in l["conditions"]: yield l["id"], "conditions", t
        for e in l["examples"]: yield l["id"], "examples", e["text"] + " " + e["note"]
    for q in Q:
        for k in ("text","answer","explanation","correctionIfFalse"): yield q["id"], k, q[k]
        for o in q["options"]: yield q["id"], "options", o
for i, k, t in visible_texts():
    if BAD.search(t): errs.append(f"{i}.{k}: internal editorial note in visible text: {t[:60]}")
cnt = collections.Counter(q["type"] for q in Q)
print("questions:", len(Q), dict(cnt))
print("lessons:", len(L["lessons"]), "corrections:", len(C))
perlesson = collections.Counter(l for q in Q if q["type"] in ("true_false","mcq") for l in q["lessonIds"])
print("lessons without auto-graded questions:", sorted(lids - set(perlesson)))
if errs:
    print("FAIL"); [print(" -", e) for e in errs]; sys.exit(1)
print("OK")
