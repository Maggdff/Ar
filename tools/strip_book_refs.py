#!/usr/bin/env python3
"""يزيل عبارات الإحالة إلى الكتاب («كما ورد في الكتاب»، «بحسب الكتاب»، «مثال الكتاب» …) من النصوص الظاهرة."""
import json, pathlib, re
ASSETS = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets"

VERB = r'(?:ورد|وردت|وردا|ورد ذكره|نص|نصّ|أكد|أكّد|فصّل|فصل|وصفه|وصفها|ذكر|ذكرها|ذكره|يفهم)'
RULES = [
    # مثال/شاهد الكتاب على X  ->  مثال/شاهد على X
    (r'\b(مثال|أمثلة|شاهد|شواهد)\s+الكتاب\s+(?:الصريحة\s+)?على\b', r'\1 على'),
    (r'\(مثال الكتاب:', '(مثال:'),
    (r'^قائمة الكتاب لأصحاب', 'قائمة أصحاب'),
    # كما ورد/نص ... الكتاب
    (r'\s*[،,]?\s*كما\s+' + VERB + r'\s+(?:في\s+)?الكتاب(?:\s+صراحة)?(?:\s+في هذا المثال بعينه)?', ''),
    (r'\s*[،,]?\s*كما\s+' + VERB + r'\s+الكتاب(?:\s+صراحة)?(?:\s+في هذا المثال بعينه)?', ''),
    (r'\s*[،,]?\s*كما\s+(?:وردت|ورد)\s+في\s+الكتاب', ''),
    (r'\s*[،,]?\s*كما\s+ذكر(?:ه|ها)?\s+الكتاب', ''),
    # بحسب الكتاب / في الكتاب / في قائمة الكتاب ...
    (r'\s+(?:بحسب|حسب|وفق)\s+(?:ما\s+ورد\s+في\s+|تقسيم\s+|ترتيب\s+|قائمة\s+|تصنيف\s+)?الكتاب', ''),
    (r'\s+المذكور(?:ة|ين|ات)?\s+في\s+الكتاب', ''),
    (r'\s+(?:الواردة|الوارد|التي وردت|الذي ورد)\s+في\s+(?:تمارين\s+|تدريبات\s+)?الكتاب', ''),
    (r'\s+(?:من أصحاب المعلقات|من الصعاليك)\s+المذكورين\s+في\s+الكتاب', ''),
    (r'\s+في\s+قائمة\s+الكتاب', ''),
    (r'\s+حسب\s+تصنيف\s+الكتاب', ''),
    (r'طريقة الكتاب:', 'الطريقة:'),
    (r'، وهذا الفرق أكده الكتاب صراحة في درس الأفعال الخمسة', ''),
    (r"رواية الكتاب لأصحاب المعلقات تضم عشرة شعراء \(رواية 'المعلقات العشر'\)", "تضم رواية 'المعلقات العشر' عشرة شعراء"),
]
def clean(s):
    for p, r in RULES:
        s = re.sub(p, r, s)
    s = re.sub(r'\s+([،.؟:)])', r'\1', s)
    s = re.sub(r'\s{2,}', ' ', s).strip()
    return s

if __name__ == "__main__":
    lp, qp, dp = ASSETS/"lessons.json", ASSETS/"questions.json", ASSETS/"diagrams.json"
    ld = json.loads(lp.read_text(encoding="utf-8")); qd = json.loads(qp.read_text(encoding="utf-8")); dd = json.loads(dp.read_text(encoding="utf-8"))
    manual = json.loads((pathlib.Path(__file__).parent/"book_ref_manual.json").read_text(encoding="utf-8")) if (pathlib.Path(__file__).parent/"book_ref_manual.json").exists() else {}
    for l in ld["lessons"]:
        for f in ("ruleText","summary","differences","commonErrors"): l[f] = clean(l[f])
        l["conditions"] = [clean(c) for c in l["conditions"]]
        for e in l["examples"]: e["note"] = clean(e["note"])
    for q in qd:
        for f in ("text","explanation","correctionIfFalse"):
            q[f] = manual.get(q["id"]+"."+f, clean(q[f]))
    for d in dd:
        d["hint"] = clean(d["hint"])
        for it in d.get("items", []): it["body"] = clean(it["body"]); it["example"] = clean(it["example"])
    lp.write_text(json.dumps(ld, ensure_ascii=False, indent=1), encoding="utf-8")
    qp.write_text(json.dumps(qd, ensure_ascii=False, indent=1), encoding="utf-8")
    dp.write_text(json.dumps(dd, ensure_ascii=False, indent=1), encoding="utf-8")
    print("done")
