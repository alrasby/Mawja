# الإصدار 48 — دمج صوت القنوات في الأخبار

- الموافقة: إزالة التبويب المكرر من الهاتف وAndroid Auto، مع إبقاء القنوات ضمن الأخبار وفلاتر الدولة واللغة والبحث.
- التصنيفات المشتركة ثمانية؛ لم تتغير روابط المصادر السبعة أو معرّفاتها أو المفضلة. وصف المصدر: «بث تلفزيوني صوتي» / TV broadcast audio.
- توافق مع اختصارات radio:tv_audio القديمة ومسارات الدولة/اللغة: تعاد إلى الأخبار، ويحتفظ getItem بالمعرّف المطلوب.
- الملفات الأساسية: RadioRepository.kt، CarRadioBrowse.kt، PlaybackService.kt، ReviewedRadioCatalog.kt، resources strings، versionCode=48، اختبارات التصنيفات/الكتالوج/التصفح، release-ui-smoke.py.
- المصدر المختبر: 7eb805f9c61bacf52a4cfde9950cba6b9987dd81.
- CI: https://github.com/alrasby/Mawja/actions/runs/45933521429 — نجاح المراحل الأربع: build وruntime-profile وrelease وrelease-smoke.
- نجحت الوحدات وثلاثة اختبارات Android. القنوات السبعة ready=true وerror=null؛ لا تغيير لسياسة تعطيل مسار الفيديو.
- نجحت واجهة APK Release المحسّن والموقع: الإعدادات والثيم واللغة والدوران، اختيار العربية وفرنسا في الأخبار، غياب التبويب المكرر، وتشغيل فرانس24.
- artifact المحاكي 15170276085؛ فحص APK الموقع 15170400382؛ APK 15169104674؛ AAB 15169279255؛ التحقق 15169134780.
- شهادة SHA256: 63d1df1550d1541aaa87f48627c37256e5bac4af4ddfbcf85618da2cdc29c676 — نفس المفتاح المخزن السابق.
- APK bytes=4643760؛ SHA256=2de0725780d5b246438b051ce991f6eabbbc6462c20d4a1bc7d264520c752098.
- AAB bytes=9310585؛ SHA256=7d3cedb578a20367f9f5ddf163a4323cc5ab8a4fa8797e4aa1d972b88c14f52a.
- نجح apksigner وjarsigner وbundletool validate ومطابقة الحزمة والإصدار والشهادتين وdebuggable=false. تطابقت بصمات نسختي التسليم مع تقرير CI.
- لا تجربة سيارة فعلية ولا رفع إلى Play Console؛ نجاح المحاكي ليس ضمانًا لكل سيارة أو منطقة بث.
