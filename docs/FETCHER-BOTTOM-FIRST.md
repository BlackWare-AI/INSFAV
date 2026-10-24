# Fetcher: sondan başa işleme ve 0.5.0 karşılaştırması

Kapsam: INSFAV içindeki Thumbnail Fetcher'ın iş seçme sırası. Arayüz, görsel motoru, SAF/rescan, oturum doğrulaması, zamanlama, worker sayısı, hata sınıflandırması ve veritabanı şeması değiştirilmez. Sürüm 5.0.0 / build 10 korunur.

## Değişiklik

| Dosya / metot | Önce | Sonra | Test / kanıt |
| --- | --- | --- | --- |
| `QueueStore.java / eligible()` | PENDING öncelikli, konum artan | Uygun işler JSON konumu azalan sırada | `QueueOrderTest`, `QueueReliabilityTest`; UNIT_TEST |
| `QueueStore.java / next()` | İlk PENDING, ardından ilk kesilmiş iş | Listenin en altındaki uygun iş | `QueueOrderTest`; UNIT_TEST |
| `QueueStore.java / pending()` | Konum artan ayrı sorgu | Aynı uygunluk ve sondan başlama sırası | `QueueOrderTest`, scheduler testleri; UNIT_TEST |
| `IntegratedEngineTest`, `QueueReliabilityTest` | Hatalı profil test verisinin başındaydı | Hatalı profil test verisinin sonunda; yürütmede yine önce hata, sonra başarı | Mevcut hata/devam beklentileri korunur; UNIT_TEST |
| `PoolTest`, `ProfileOutcomeHistoryTest` | Eski baştan başlama sırası bekleniyordu | Aynı hesaplar ve korunmuş geçmiş için yeni sondan başlama sırası beklenir | Mevcut kayıt/dosya/Retry beklentileri korunur; UNIT_TEST |
| `QueueOrderTest` | Yoktu | Sıra, kesilen iş, yeniden açma, JSON ekleme/sıralama, tekrarlar ve açık Retry senaryoları | Android 28 ve 34 Robolectric; UNIT_TEST |
| `QueueReliabilityTest / bottomFirstStartPauseResumeAndNewJsonAccountKeepSavedFiles()` | Yoktu | Gerçek koordinatör, sahte sağlayıcı ve SAF fixture ile Start → durdur → Resume → JSON ekleme | Dosya baytları ve klasör listeleme sayısı korunur; UNIT_TEST |

`position` mevcut JSON'daki benzersiz hesap sırasıdır; favoriye eklenme zaman damgası değildir. Listenin sonuna eklenen yeni hesaplar önce işlenir. JSON başka bir sırada dışa aktarılırsa tarama yine mevcut listenin altından başlar. Yeni bir tarih/indeks alanı veya şema geçişi eklenmedi. Normal liste/klasör gösterim sırası değişmez.

SUCCESS ve EXISTING yeniden indirilmez. SKIPPED, FAILED ve denemesi kaydedilmiş RETRY_LATER hesapları açık Retry seçilmedikçe iş listesine alınmaz. Kesinti nedeniyle RETRY_LATER olan fakat deneme harcamamış işler Resume sırasında konumlarına göre devam eder. JSON'dan kaldırılıp yeniden eklenen başarısız hesapların mevcut özel sonuç kayıtları korunur. Yeni hesaplar bu eski başarısızlık kayıtlarıyla karıştırılmaz.

## Ayrı Thumb Fetcher 0.5.0 incelemesi

Salt okunur referans: [BlackWare-AI/INSFAV-Thumb-Fetcher v0.5.0](https://github.com/BlackWare-AI/INSFAV-Thumb-Fetcher/tree/401631af687a01cd062e6ee7169b0f576c751759), yayın adı 0.5.0 / build 9. Ayrı depoda değişiklik yapılmadı.

- Referans `QueueEngine.run()` kalan hedefler veya uçuşta işler olduğu sürece devam eder; beş profil sonra bitiren sayaç bulunmaz. `single` zamanlanmış sınırlı işe aittir.
- Referans `QueueStore.preflightCandidates()` içinde `LIMIT 5` bulunur, ancak bu sürümün üretim kodunda çağrısı bulunmaz. Entegre INSFAV bu metodu kullanmaz. Bu limit beş işlemden sonraki durmanın kanıtı değildir.
- Referans `FailurePolicy.skip()` WEBVIEW_TIMEOUT, görsel eksikliği ve belirsizlik dahil birçok geçici sonucu SKIPPED sayar. Entegre sürüm bu sonuçları RETRY_LATER ve açık Retry geçmişi olarak korur. Bu farklı politika kopyalanmadı.
- Her iki sağlayıcıda 500 ms poll, profil için 45 saniyelik inceleme deadline'ı, 55 saniyelik watchdog ve 65 saniyelik bekleme üst sınırı vardır. Entegre sürümde broker ayrıca 110 saniyelik işlem sınırı uygular. Bunlar işlem başına üst sınırlardır; canlı cihazda tekrarlanan durmanın nedenini tek başlarına kanıtlamazlar.
- Entegre `QueueEngine.run()` profil timeout, bulunamama ve profil kaynaklı RuntimeException sonrasında devam eder. Oturum doğrulama belirsizliği güvenli beklemeye; geçersiz oturum, challenge, rate limit, izin/veritabanı/depolama/bellek sorunları uygun duraklatmaya gider. Çalışma penceresi ve kullanıcı tarafından belirlenen worker gecikmeleri de korunur.
- `FetchBroker` aynı anda kapasite kadar iş başlatır, her worker'ın mevcut başlangıç aralığını bekler. Beş worker'ın ilk turundan sonraki bekleme, tek başına bir durma hatasının kanıtı değildir. Worker sayısı veya bekleme aralıkları değiştirilmedi.

**Sonuç:** Sıra değişikliği kodla doğrulanmış ve test edilebilir. Cihazdaki tekrarlanan durmanın nedeni güncel diagnostics/hata durumu olmadan doğrulanamaz. Bu yayın, “bütün takılmalar giderildi” iddiasında bulunmaz ve doğrulanmış oturum/rate-limit/depolama engellerini yok saymaz.

## Doğrulama ve sınırlamalar

Yeni ve mevcut testler sondan başlama, hata sonrası devam, beş worker timeout'undan sonra sonraki işlere devam, Pause/Resume, JSON değişimi, başarısızlık geçmişi ve kalıcı indeksin yeniden kullanımını kapsar. Offline/Robolectric fixture'lar gerçek Instagram veya fiziksel cihaz testi değildir. Test/lint sayıları ve sonuçları release içindeki `verification.json` ve raporda kaynak commit ile belirtilir.

İlk hedefli çalışmada yeni duraklama testi başarısız oldu: seyrek iletilen ilerleme callback'iyle durdurma kurulduğundan üç profil de tamamlandı. Testin tek-profil beklentisi değiştirilmedi; doğrudan koordinatörün `BooleanSupplier stopped` kontrolü kullanıldı. Ürün bildirim/pause davranışı bu nedenle değiştirilmedi. Sonuçlar yeniden çalıştırılarak raporlanır.

İlk tam birim test çalışması 238 testten üçünü eski artan sıra beklentisi nedeniyle başarısız bildirdi (`PoolTest` ve iki Android SDK üzerinde `ProfileOutcomeHistoryTest`). Beklenen hesap kümeleri, statüler ve dosya koruma kontrolleri değişmeden yalnızca beklenen sıra yeni azalan sıraya güncellendi; testler kaldırılmadı veya devre dışı bırakılmadı.
