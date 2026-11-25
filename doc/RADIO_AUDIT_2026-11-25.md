# تدقيق الراديو — 2026-11-25

## النتائج

طلب news مع اللغة العربية أعاد 87 محطة. وسم الأخبار مستخدم أيضًا لمحطات موسيقية ودينية وعامة. اعتمد التصنيف الجديد مجموعة محافظة من الخدمات المراجعة بالاسم مع اشتراط وسوم إخبارية ورفض الوسوم المختلطة. الخدمات المسموح بها حاليًا هي العربية FM والعربي وBBC Arabic وREPLAY NEWS العربية. توسيع القائمة يتطلب مراجعة هوية الخدمة وبرامجها. تصنيف الخدمة الإخباري لا يضمن أن كل ثانية من البث أخبار.

أصبح مفتاح ذاكرة الأخبار news_v2 لتجنب استرجاع نتائج التصنيف السابق. يرفض النشر روابط HTTP التي تحظرها سياسة الشبكة الحالية والروابط غير الصالحة وبيانات الدليل التي تشير إلى فشل البث أو SSL. تُحفظ حالة الأحرف في مسار الرابط عند إزالة التكرار. يُتحقق من بلد المحطات المحلية، وتُجرى قراءة وكتابة الذاكرة على IO.

## فحص البث

استخدم ffprobe بمهلة 15 ثانية لكل رابط وتوازٍ أقصاه 5. نجاح فك معلومات الصوت يثبت وصول بيانات صوتية في هذه البيئة وقت الفحص. انتهاء المهلة يحتاج إعادة تحقق من شبكة أخرى. البيانات من Radio Browser قابلة للتحرير ولا تثبت ملكية رابط البث.

| المحطة | نتيجة فحص الصوت | الرابط |
|---|---|---|
| Al Arabiya FM | تعذر التأكيد خلال المهلة | https://fm.alarabiya.net/fm/myStream/playlist.m3u8 |
| Al araby | aac | https://l3.itworkscdn.net/alarabyradiolive/alarabyradio_audio/icecast.audio |
| BBC Arabic  | mp3 | https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio |
| إذاعة الأخبار كل 5 دقائق   REPLAY NEWS - العربية | mp3 | https://replaynewsar.ice.infomaniak.ch/replaynewsar-128.mp3 |
| 90.4 FM   الإذاعة-الإنجليزية | تعذر التأكيد خلال المهلة | https://partwota.cdn.mgmlcdn.com/omengrdoorg/omengrdo.stream_aac/chunklist.m3u8 |
| Al Wisal | تعذر التأكيد خلال المهلة | http://uk7.internet-radio.com:8067/; |
| Hala FM | mp3 | https://listen-halafm.sharp-stream.com/halafmlow.mp3 |
| Hi FM Oman | mp3 | http://listen-hifmtemp.sharp-stream.com/hifmmid.mp3 |
| JazzWave Radio | mp3 | https://servidor19.brlogic.com:7126/live |
| Merge 104.8 | تعذر التأكيد خلال المهلة | http://uk7.internet-radio.com:8040/ |
| Quran Program Oman   إذاعة-القرآن-الكريم | aac | https://partwota.cdn.mgmlcdn.com/quranrdoorg/quranrdo.stream_aac/chunklist.m3u8 |
| SpiritFlow Sounds Radio | mp3 | https://radio.spiritflowsounds.com/listen/spiritflow-sounds-radio/radio.mp3?utm_source=chatgpt.com |
| Virgin Radio (Oman) | mp3 | http://uk5.internet-radio.com:8115/stream |

## حدود التحقق

المحلية تخص بلد المستخدم عالميًا. فُحصت المحطات العُمانية الظاهرة في الدليل عند هذا التدقيق. بقية البلدان تحتاج فحصًا بحسب النتائج الفعلية. بعض روابط HLS قد تحتاج مهلة أطول أو شبكة محلية مختلفة. لم يُجرَ اختبار هاتف أو سيارة في هذا التدقيق.

## مصادر مراجعة التصنيف

- https://www.alaraby.com/radio-live
- https://www.replaynews.net/index.php?lang=ar
- https://www.alarabiya.net/ar/fm
- https://docs.radio-browser.info/

## إعادة التحقق والاختبارات

في إعادة طلب GET بمهلة 10 ثوانٍ، أعاد كل من العربية FM وإذاعة عُمان الإنجليزية HTTP 200 وقائمة HLS صحيحة. أعادت الوصال وMerge HTTP 200 وبيانات audio/mpeg. لم يُثبت هذا الاختبار فك مقاطع HLS التابعة للعربية أو إذاعة عُمان الإنجليزية. نجح testDebugUnitTest وبناء assembleDebug في GitHub Actions رقم 45560719199 عند الالتزام 9e76dfc1cb3f666c4f2b021f483f8aadf935da45. الاختبارات الثلاثة تغطي استبعاد التصنيفات المختلطة وقبول الخدمات المراجعة وسياسة الروابط.
