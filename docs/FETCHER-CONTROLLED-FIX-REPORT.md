# Thumbnail Fetcher: kontrollü tanılama ve düzeltme

Kaynak: BlackWare-AI/INSFAV `main`, `4d442c5cd52f5edcdfdc35e7bb2701912c5afda2`.
Kapsam: ilk iki çalışma paketi (0–6). Normal görüntüleme, SAF genel indeksi,
Rescan Thumbnails, JSON formatı ve favori klasörleri kapsam dışındadır.

## Adım 0 — Değişiklik öncesi kaynak incelemesi

Java dosyalarının gerçek kökü `app/src/main/java/com/instagram/insfav/fetcher/`.
Probe: `app/src/main/assets/profile-probe.js`.

| Dosya / metot | Referans commit'teki davranış | Kanıt |
|---|---|---|
| `profile-probe.js` / ana fonksiyon, `walk()` | Görünür unavailable metni ve `rootView.resource.__dr == PolarisErrorRoot.react`, görsel aranmadan `PROFILE_PAGE_UNAVAILABLE` üretir. Canonical yoksa location kullanılır. | CODE_REVIEW |
| `WebViewProfileProvider` / `Operation.poll()` | Tek wall snapshot'ı (HTTP ölçülmeden 200 eklenerek) tamamlanır. 45 s sonunda profil için `PROFILE_IMAGE_URL_MISSING`, auth için `COOKIE_AUTH_UNVERIFIED`. | CODE_REVIEW |
| `WebViewProfileProvider` / `Operation.start()`, `resolve()` | Profil için 55 s watchdog, 65 s await; auth için 18 s watchdog, 25 s await. Poll 500 ms; bu inceleme gerçek zamanlama çakışmasını kanıtlamaz. | CODE_REVIEW |
| `WebViewProfileProvider` / `applySessionCookies()`, `authenticate()` | Callback false ise `COOKIE_WEBVIEW_REJECTED`. Başarılı aktarım yalnızca `SHARED_COOKIES_APPLIED`. Authenticate boş kullanıcıyla viewer kimliği arar. | CODE_REVIEW |
| `FailurePolicy` / `unavailable()`, `noImage()`, `pause()` | Unavailable -> SKIPPED_UNAVAILABLE; missing URL, identity ve ambiguity -> SKIPPED_NO_IMAGE. WEBVIEW_TIMEOUT global barrier değildir. | CODE_REVIEW |
| `Fetcher` / `action()`, `sync()` | Kuyruk ve kalıcı metadata hazırlanır; batch için authenticate çağrılmaz. | CODE_REVIEW |
| `FetchBroker` / `loop()`, `execute()`, `block()` | Provider doğrudan fetch'e gider. Her COOKIE_ hatası sessionEvidence=INVALID yapar; auth kanıtı ile setup hatası karışır. RuntimeException profil koduna çevrilir; OOM yalnızca concurrency azaltır. | CODE_REVIEW |
| `QueueEngine` / `run()` | Per-profile commit RuntimeException dış catch'e çıkarak bütün kuyruğu PROCESSING_ERROR ile durdurur. IOException profil hatası sayılır. Dış catch OOM'u da genel işlem hatası olarak yutar. | CODE_REVIEW |
| `DownloadWorker` / `doWork()` | Bütün Exception'lar SYSTEM_RESTRICTED olur. | CODE_REVIEW |
| `QueueStore` / `prepareMissing()`, `sync()`, `recoverInterrupted()` | SQLite v4 kalıcı indeks. Start Missing FAILED/RETRY_LATER deneme geçmişini sıfırlar. Sync kullanıcıları normalize ve dedupe eder; çıkarılan hesap satırını siler, thumbnail silmez. | CODE_REVIEW |
| `RunPolicy` / `contains()`, `nextWindow()` | Varsayılan 02:00–07:00; gece yarısını aşan aralık desteği var. | CODE_REVIEW |
| `ConcurrencyPolicy` / `initial()`, `budget()`, `reduced()` | Kullanıcı ayarı ve tahmini bellek bütçesiyle admission; bu rakamlar ölçülmüş WebView maliyeti değildir. | CODE_REVIEW |

Kullanıcının önceki diagnostics sayıları DIAGNOSTICS girdisidir; asıl dosya bu
çalışmada verilmedi. HTTP 200 ne erişilebilirliği ne kapanmayı kanıtlar.
UNKNOWN, diagnostics içinde doğrulama kanıtı bulunmadığını belirtir.
PolarisErrorRoot'un tek başına kapanmış hesap anlamına geldiğine dair kanıt yoktur.

## Paket 1 — Tanılama

- `profile-probe.js`: sabit `reason`, `pageReady`, `candidateFound`, `searchCompleted` alanları.
- `WebViewProfileProvider.Operation.poll()/finish()/client()`: alt kod, gerçekten
  bilinen HTTP durumu (0=bilinmiyor), yükleme/görsel/session bilgisi ve süre.
- `Diagnostics.outcome()/session()/export()`: safe alanları dışa aktarır;
  bilinmeyen alt kod UNKNOWN_RESPONSE olur. DOM, HTML, profil adı, URL ve cookie yazılmaz.
- `FetchBroker.beginBatch()/submit()/loop()`, `QueueControl`: sayısal batch ve
  kuyruk metrikleri. SESSION olayları batch numarası taşır. Çerez uygulama auth değildir.
- Testler: `DiagnosticsTest.structuredOutcomesAndBatchSessionEventsSurviveExportWithoutProbePayload`,
  `tests/probe-classification.cjs` (tamamen intercept edilen offline DOM fixture'ları).

İlk Gradle denemeleri ortam eksikleri (proxy ve JDK compiler) nedeniyle durdu;
bunlar test assertion sonucu değildir. Nihai test sonuçları aşağıya eklenecektir.

Paket 1 sonucu: DiagnosticsTest **2/2 geçti**; offline DOM **5/5 geçti**;
`:app:lintDebug` başarılı. Henüz DEVICE_TEST veya gerçek Instagram kanıtı yok.
