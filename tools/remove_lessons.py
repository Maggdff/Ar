#!/usr/bin/env python3
"""يحذف الدروس المُزالة من التطبيق مع اختباراتها (يُستدعى بعد apply_fixes، ويمكن تشغيله على ملفات assets مباشرة)."""
import json, pathlib
HERE = pathlib.Path(__file__).resolve().parent
ASSETS = HERE.parent / "app/src/main/assets"

REMOVED = {
    "U1-L15",  # الجملة الاسمية
    "U2-L06",  # كتابة الألف المقصورة
    "U2-L07",  # كتابة الضاد والظاء
    "U2-L08",  # الحروف التي تزاد في الرسم الإملائي
    "U2-L09",  # الحروف التي تحذف في الرسم الإملائي
    "U3-L06",  # عصر صدر الإسلام والعصر الأموي
    "U3-L07",  # العصر العباسي وعصر الدويلات والعصر الحديث
    "U3-L09",  # الأمثال العربية
}
# أسئلة شاملة تعتمد على أبواب محذوفة (ترتيب الأبواب يشمل الضاد والظاء والأمثال)
REMOVED_QUESTIONS = {"Q0148"}

def apply(units, lessons, questions, corrections):
    for u in units:
        u["lessonIds"] = [i for i in u["lessonIds"] if i not in REMOVED]
    lessons[:] = [l for l in lessons if l["id"] not in REMOVED]
    qs = []
    for q in questions:
        if q["id"] in REMOVED_QUESTIONS: continue
        ids = q["lessonIds"]
        if ids and all(i in REMOVED for i in ids): continue
        q["lessonIds"] = [i for i in ids if i not in REMOVED]
        qs.append(q)
    questions[:] = qs
    cs = []
    for c in corrections:
        ids = c["lessonIds"]
        if ids and all(i in REMOVED for i in ids): continue
        if any(i in REMOVED for i in ids):
            c["lessonIds"] = [i for i in ids if i not in REMOVED]
            c["lessonRef"] = " / ".join(c["lessonIds"])
        cs.append(c)
    corrections[:] = cs

if __name__ == "__main__":
    rd = lambda n: json.loads((ASSETS / n).read_text(encoding="utf-8"))
    wr = lambda n, o: (ASSETS / n).write_text(json.dumps(o, ensure_ascii=False, indent=1), encoding="utf-8")
    ld, qd, cd = rd("lessons.json"), rd("questions.json"), rd("corrections.json")
    apply(ld["units"], ld["lessons"], qd, cd)
    wr("lessons.json", ld); wr("questions.json", qd); wr("corrections.json", cd)
    print(f"lessons={len(ld['lessons'])} questions={len(qd)} corrections={len(cd)}")
