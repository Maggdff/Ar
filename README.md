# OCR PDF Arabic & English

تطبيق Android أصلي (Kotlin + Jetpack Compose) لاستخراج النص من ملفات PDF الممسوحة ضوئيًا بالعربية والإنجليزية، **دون إنترنت**.
Native Android app (Kotlin + Compose) that OCRs scanned PDFs in Arabic and English, fully offline. The manifest has **no INTERNET permission**.

## الميزات / Features
| | |
|---|---|
| اختيار الملفات | Storage Access Framework (`OpenMultipleDocuments`) + "فتح بواسطة/مشاركة" من تطبيقات أخرى |
| اكتشاف الصفحات المصورة | `PDFTextStripper` (PDFBox) — الصفحة بلا نص قابل للاستخراج تُعالج بـ OCR، والباقي يُنسخ نصه الأصلي |
| OCR | Tesseract 5 (LSTM) عبر Tesseract4Android، نماذج `ara` و`eng` (tessdata_fast) داخل الـ APK؛ عربية / إنجليزية / كلتاهما |
| صفحات متعددة | معالجة صفحة بصفحة، تقدم + إشعار، **إلغاء** فوري (يوقف Tesseract أثناء الصفحة)، نقاط حفظ لكل صفحة |
| عرض النص | كل فقرة بـ `TextDirection.Content` ⇒ RTL للعربية وLTR للإنجليزية تلقائيًا، قابل للتحديد |
| تصدير | نسخ، مشاركة، حفظ TXT، حفظ Markdown (فقرات RTL داخل `<div dir="rtl">`) |
| PDF قابل للبحث | الصفحات الأصلية تبقى كما هي حرفيًا، وتُضاف فوقها طبقة نص **غير مرئية** (render mode 3) بخط Amiri المضمّن — نفس تقنية OCRmyPDF |
| ضغط اختياري | يعيد ترميز الصور الكبيرة 8-bit فقط (JPEG) بعد تصغير آمن؛ لا يمس صور 1-bit (CCITT/JBIG2) ولا الصور ذات الأقنعة؛ يُحتفظ بالنتيجة فقط إن صغُر الحجم ≥10% |
| الواجهة | عربية RTL افتراضيًا + English، وضع فاتح/داكن/نظام، لغة التطبيق من الإعدادات |
| السجل والاستكمال | Room: الحالة والتقدم والأخطاء؛ "استكمال" يتخطى الصفحات المحفوظة؛ WorkManager يعيد المهمة بعد قتل العملية |
| الذاكرة والبطارية | مهمة واحدة في كل مرة، حد أقصى لبكسلات الصفحة حسب ذاكرة الجهاز (يخفض DPI تلقائيًا)، PDFBox بوضع الملفات المؤقتة، خيار "لا تعالج عند انخفاض البطارية" |

## البناء / Build
المتطلبات: JDK 17 و Android SDK (API 35) — أو Android Studio Ladybug+.
```bash
git clone <your-repo> && cd <your-repo>
./gradlew testDebugUnitTest        # اختبارات الوحدة
./gradlew assembleDebug            # app/build/outputs/apk/debug/*.apk
./gradlew assembleRelease          # APK موقّع بمفتاح debug للتجربة
./gradlew connectedDebugAndroidTest   # اختبارات OCR الشاملة على جهاز/محاكي
```
يُنتج البناء APK واحدًا شاملًا (`app-debug.apk` / `app-release.apk`) يعمل على جميع المعماريات (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
بدون Android Studio: ارفع المشروع إلى GitHub، وسيبني `.github/workflows/build.yml` الـ APK ويرفعه كـ Artifact.

**للنشر الفعلي**: أنشئ keystore وأضف `signingConfigs.release` في `app/build.gradle.kts` (الإعداد الحالي يوقّع release بمفتاح debug للتسهيل فقط).

## البنية / Architecture (مستوحاة من OCRmyPDF)
OCRmyPDF (MPL-2.0) أنبوب Python/Ghostscript/Tesseract لا يعمل على Android. أُعيد تنفيذ فكرته بمكتبات متوافقة، **دون نسخ أي كود منه**:

| OCRmyPDF | هذا التطبيق |
|---|---|
| `--skip-text` (اكتشاف الصفحات ذات النص) | `NativeTextProbe` + خيار "معالجة كل الصفحات" |
| rasterize (Ghostscript) | `android.graphics.pdf.PdfRenderer` → `PdfSupport.render` |
| OCR (Tesseract → hOCR) | `OcrEngine` (Tesseract4Android `ResultIterator`، كلمات + صناديق) |
| hocrtransform: نص غير مرئي بخط glyphless | `TextLayerWriter`: render mode 3 + خط Amiri، مع تمديد أفقي ليطابق صندوق الكلمة، ودعم `/Rotate` وCropBox (`TextLayout`) |
| merge فوق الصفحة الأصلية | `PDPageContentStream` بوضع APPEND فوق الصفحة الأصلية (الصور لا تُعاد كتابتها) |
| optimize (pngquant/jbig2/jpeg) | `PdfCompressor` (JPEG محافظ فقط) |

الملفات: `ocr/` (المحرك والنماذج) · `pdf/` (RENDER, الطبقة, الضغط) · `work/` (`OcrPipeline`, `OcrWorker`, `JobRepository`, نقاط الحفظ) · `data/` (Room, الإعدادات) · `export/` · `ui/`.

## الرخص / Licenses — انظر `docs/LICENSES.md`
كود التطبيق: Apache-2.0 (`LICENSE`). كل التبعيات والنماذج متوافقة (Apache-2.0 / OFL-1.1) ولا تتطلب مشاركة المصدر.

## قيود معروفة / Known limitations
- **لم يُبنَ هذا المشروع ولم تُشغَّل اختبارات الأجهزة في بيئة الإنشاء** (لا وصول لـ Google Maven/Gradle هناك). تم التحقق فقط من منطق الهندسة والتصدير بـ kotlinc. أول بناء قد يحتاج تعديلات بسيطة في توقيعات مكتبات Tesseract4Android/PDFBox-Android — الاختبارات الشاملة في `androidTest` مصممة لكشفها.
- جودة العربية تعتمد على جودة المسح؛ النماذج `fast` أصغر وأسرع لكنها أقل دقة من `best` (استبدل ملفي `app/src/main/assets/tessdata/*.traineddata` بنسخ `tessdata_best` إن أردت دقة أعلى وحجمًا أكبر).
- النص المخفي يُكتب بالترتيب المنطقي (Unicode) لكل كلمة؛ سلوك النسخ/البحث للعربية يختلف قليلًا بين قارئات PDF.
- لا يوجد تصحيح ميلان/تدوير تلقائي (OSD). صور PDF محمية بكلمة مرور تُرفض برسالة واضحة.
- "معالجة كل الصفحات" يضيف طبقة نص فوق نص أصلي موجود، فقد يتكرر النص في البحث.
