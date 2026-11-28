# البناء54 — أيقونة موجة الفضية/التركوازية المعتمدة

- وافق المستخدم على نموذج الأيقونة، وطلب تطبيقه. استخدمت الصورة المعتمدة نفسها1254×1254 دون إعادة توليد أو تعديل بكسلاتها.
- أصل PNG حجمه1690463bytes، Git blob SHA1=a22260a50575f52dff2de9ccc375b515a0bc99c5، مطابق للملف الذي أُقرّ في المعاينة.
- استبدال mawja_icon_clean.png؛ Manifest يشير للأيقونة الجديدة العادية والمستديرة ic_launcher_mawja.
- تعريف adaptive علىAPI26+: خلفية تركوازية كاملة، ومقدمةinset16% حول الصورة لتجنب قص العلامة؛ تعريف bitmap بديل. الصورة مصدر واحد مشترك، دون نسخ PNG متعددة للكثافات.
- لم تتغير الأزرار أو الشاشات أو بطاقة الحقوق أو المشغل أو الصلاحيات. تأكد المستخدم أثناء المهمة أن المطلوب الأيقونة فقط؛ لم تُسترجع مرحلةAll السابقة.
- الملفات: AndroidManifest.xml، mipmap-nodpi/mawja_icon_clean.png، mipmap-anydpi*/ic_launcher_mawja.xml، drawable/mawja_icon_{background,foreground}.xml، ThemeRegressionTest.kt وخط حفظ صورة الأيقونة فيCI، versionCode54 / versionName1.0.0.
- المصدر المختبر والموقع: d2f0e2448fd6450017883feb3968443f6be115f2.
- CI: https://github.com/alrasby/Mawja/actions/runs/46102971729 — نجاح المراحل الأربع build/runtime-profile/release/release-smoke.
- نجاح الوحدات و4 اختباراتAndroid والواجهة ودورة الحياة. اختبار الأيقونة عبر PackageManager يثبت أنها AdaptiveIconDrawable وأن المصدر1254px، ويصدر رسمAndroid الفعلي launcher-adaptive.png. راجعت الرسم بصريًا: الموجات وأعمدة الصوت كاملة داخل الهوامش.
- لا ادعاء بتجربة كل مشغلات الأجهزة أو أشكالOEM؛ المراجعة الفعلية على محاكيAPI35. أصل الصورة باقٍ كما اعتُمد، والقناع يطبقه النظام.
- نجح فحصAPK Release الموقع من الواجهة والتنقل والثيم واللغة والدوران وثبات الحقوق والتشغيل الحي لفرانس24؛ سجل الإغلاق النهائي فارغ.
- artifacts: المحاكي15246199362 (يتضمنlauncher-adaptive.png)؛ APK15246229849؛ AAB15246399575؛ التحقق15246324508؛ فحصAPKالموقع15246782585.
- شهادة المفتاح السابق SHA256=63d1df1550d1541aaa87f48627c37256e5bac4af4ddfbcf85618da2cdc29c676.
- APK bytes=5563300؛ SHA256=ebdad966d8fa2e60842178c1ec4095eab9f92eceae2dbfc6d3a2ff71daaf94f5.
- AAB bytes=10271412؛ SHA256=3f92d723fd6712b6a604fa29647057dea77658ce6cb623896a79ceafdf18917e.
- زيادة حجمAPK عن53 نحو0.90MB بسبب استبدال صورة الأيقونة528087bytes بالصورة المعتمدة الأعلى تفصيلًا؛ لا مكتبات جديدة.
- نجح apksigner وjarsigner وbundletool validate ومطابقة الحزمة والإصدار والشهادتين وdebuggable=false؛ نسخ التسليم مطابقة لبصماتCI.
- لا نشرPlay Console ولا تجربة سيارة أو هاتف المستخدم في هذه المهمة.
