"""Package anonymous test evidence and a Turkish development report after the real emulator matrix."""
from pathlib import Path
import json, os, shutil, hashlib, xml.etree.ElementTree as ET
from collections import Counter
import subprocess
root=Path.cwd()
build=root/"builds"
proof=root/"emulator-pool"
destination=root/"final-delivery"
rows=json.loads((proof/"pool-matrix.json").read_text())
background=json.loads((proof/"background-fixture.json").read_text())
assert all(row["sdk"]==34 for row in rows)
assert background["completed"]==8 and not background["userPaused"]
assert "POOL_MATRIX_OK" in (proof/"instrumentation.txt").read_text()
assert "BACKGROUND_HOME_TASK_OK" in (proof/"instrumentation.txt").read_text()
tests={name:0 for name in ["tests","failures","errors","skipped"]}
xml_files=list((build/"app/build/test-results").rglob("TEST-*.xml"))
assert xml_files, "Missing native test evidence"
for path in xml_files:
    suite=ET.parse(path).getroot()
    for key in tests: tests[key]+=int(suite.get(key,0))
assert tests["tests"]>=97 and not any(tests[key] for key in ["failures","errors","skipped"])
browser=(build/"browser-results.txt").read_text()
assert "78 UI checks passed" in browser
lint=dict(Counter(item.get("severity") for item in ET.parse(build/"app/build/reports/lint-results-debug.xml").getroot()))
assert not lint.get("Error",0)
shutil.copytree(build/"delivery",destination,dirs_exist_ok=True)
shutil.copytree(proof,destination/"verification/android-14",dirs_exist_ok=True)
shutil.copytree(build/"app/build/test-results",destination/"verification/native",dirs_exist_ok=True)
for path in ["browser-results.txt","probe-results.txt","counter-results.txt"]:
    shutil.copyfile(build/path,destination/"verification"/path)
shutil.copyfile(build/"app/build/reports/lint-results-debug.txt",destination/"verification/lint-results-debug.txt")
source=os.environ.get("SOURCE_COMMIT","")
five=next(row for row in rows if row["requested"]==5 and row["minimumDelaySeconds"]==1)
one=next(row for row in rows if row["requested"]==1)
realistic=next(row for row in rows if row["requested"]==5 and row["minimumDelaySeconds"]==25)
assert five["intervalOverlap"] and five["maxActiveWebViews"]>=2
assert realistic["intervalOverlap"] and realistic["maxActiveWebViews"]>=2
speed=five["completedPerMinute"]/one["completedPerMinute"]
debug=destination/"INSFAV-debug.apk"
release=build/"app/build/outputs/apk/release/app-release-unsigned.apk"
verification={
    "application":"INSFAV","version":"3.3.0","build":5,"sourceInsfavCommit":source,
    "sourceFetcherCommit":"b3f03f2a30c36f89add5e954b9b121a7b9d899ae",
    "preservedInsfavCommit":"ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1",
    "nativeTests":tests,"hostUiChecks":78,"lint":lint,
    "emulatorSdk":34,"emulatorUsesRealChromium":True,"emulatorUsesOfflineResponses":True,
    "emulatorMatrix":rows,"backgroundFixture":background,
    "speedMultiplierFiveVsOne":speed,"physicalDeviceTested":False,"liveInstagramBenchmark":False,
    "debugApkBytes":debug.stat().st_size,"releaseUnsignedBytes":release.stat().st_size,
    "debugApkSha256":hashlib.sha256(debug.read_bytes()).hexdigest(),
    "hdProbeSha256":hashlib.sha256((root/"app/src/main/assets/profile-probe-hd.js").read_bytes()).hexdigest(),
    "sourceTree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"]).decode().strip()
}
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
text=f"""# INSFAV 3.3.0 — Geliştirme ve doğrulama raporu

## Kaynaklar ve koruma
- Uygulama: INSFAV 3.3.0 / build 5, com.instagram.insfav.
- Ana INSFAV kod commit'i: {source}.
- Kaynak Fetcher dalı: BlackWare-AI/INSFAV-Thumb-Fetcher, development/0.4.0.
- Alınan Fetcher commit'i: b3f03f2a30c36f89add5e954b9b121a7b9d899ae.
- Çalışan 3.2.0 koruma commit'i: ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1; preserve-working-3.2.0 dalı.
- Bağımsız Fetcher ve arşivlenmiş çalışan sürümü değiştirilmedi.

## Darboğazlar ve gerçek eşzamanlılık
Önceki INSFAV broker'ı tek işlem kabul ediyordu. Kaynak 0.4.0 ayrıca Java memoryClass/32 ile kapasiteyi sınırlıyor ve tamamlanan her hesabın ardından global beklemeyi uzatıyordu. Yalnızca tamamlanma sonrası global beklemeyi kaldırmak da kısa profiller için yeterli değildi: tek global başlangıç takvimi onları yine seri bırakabiliyordu.

Yeni sistemde her işçi kendi kalıcı workerNextAt zamanına sahiptir. Minimum/maksimum değerler o işçinin başlangıçları arasındaki aralıktır. Bir işçinin beklemesi diğer hazır işçiyi tutmaz. Kuyruk/SAF yazımları koordinatörde sıralanır; profil gezinme, DOM ve CDN işlemleri bağımsız yürür. Retry-After, 429 ve oturum/erişim kısıtlamaları bütün havuzu duraklatır. Eşzamanlılığı artırmak toplam istek hızını artırır; engellenmeme garantisi verilmez.

Requested/Effective/Active WebViews, aktif hesaplar, tepe aktif hesaplar, hesap/dakika, ETA ve uygulama PSS bilgileri ayrıdır. İstenen 1–30 değeri kalıcıdır; varsayılan 1'dir. Java memoryClass/32 sınırı kaldırıldı. Mevcut sistem belleği, Java heap boşluğu, geçici bütçe tahminleri ve gerçek bellek baskısı callback'leri kullanılır. Tahminler fiziksel cihazda doğrulanmış sayfa başı bellek ölçümü değildir. Baskı/renderer hatasında kabul kapasitesi azaltılır; kullanıcı gerçek etkin sayıyı görür.

## Aktarılan, uyarlanan ve korunmuş bileşenler
- 0.4.0 WebView havuzu, ortak cookie lease, yeniden kullanım/temizleme, DOM probe ve 256 piksel codec aktarıldı.
- ConcurrencyPolicy, QueueEngine, broker, kalıcı kuyruk, servis/zamanlayıcı ve tanılama ana INSFAV'a uyarlandı.
- Bağımsız uygulamanın ikinci JSON/klasör seçimi ve fotoğraf görüntüleyicisi aktarılmadı; ana uygulamanın çalışan karşılıkları korundu.
- Kaynak daldaki bütün ağ hatalarını doğrudan atlama yaklaşımı aktarılmadı: geçici ağ/zaman aşımı hataları sınırlı RETRY_LATER döngüsü kullanır.
- 404/açık erişilemezlik SKIPPED_UNAVAILABLE, eksik/belirsiz fotoğraf SKIPPED_NO_IMAGE olur; fotoğraf eksikliği silinmiş hesap kanıtı değildir.
- Üç gerçek başarısız deneme FAILED olur. Genel erişim engelleri hesap deneme hakkını tüketmez. Atlanan/başarısız hesaplar açık kullanıcı isteğiyle yeniden denenebilir.
- Hesap işlemi üst süresi 110 saniyedir; WebView/DOM için daha kısa süreler ve temizleme vardır.
- Yeni çıktı .thumbnails/<username>.webp, 256×256, WebP kalite 78, merkez kırpmadır. Eski 128×128 dosyalar geçerli kalır. Legacy yenileme isteğe bağlıdır; eski dosya yeni dosya doğrulanmadan silinmez.
- Mevcut SAF klasörü, doğrulamalı rename/yedek/journal kurtarması ve yeniden taramasız indeks güncellemesi korunur.
- Store veri katmanı, JSON alanları/sıraları/üyelikleri, usage/load/clear artırma ifadeleri ve About düzeni korunur.
- AMOLED, avatar kutularının dikdörtgen biçimi/boyutu, logo, BlackWare ve OpenAI ChatGPT destek bilgisi korunur.
- Çalışan HD probe'un hash'i {verification["hdProbeSha256"]}. Yerel fotoğraf önce gösterilir; HD ortak oturumda öncelikli gelir, gerçek boyutu gösterilir, kalıcı dosyaya yazılmaz. Kapatma iptal/token ve byte referanslarını bırakır; Chromium RAM'inin aynı anda fiziksel olarak sıfırlandığı iddia edilmez.
- Cookie'ler Keystore tabanlı özel alanda tutulur; tanılamalara yazılmaz. Test Cookies ve üç-profil zorunluluğu yoktur.

## Test sonuçları
- Android/Robolectric: {tests["tests"]} test; {tests["failures"]} başarısız, {tests["errors"]} hata, {tests["skipped"]} atlanan.
- Chromium arayüz: 78 kontrol başarılı; 10.000 hesap, sanal liste, About/sayaçlar, Fetcher kontrolleri ve HD/zoom/geri dönüş dahil.
- Çekirdek JavaScript: 9 test; 1.000/5.000/10.000 kayıt senaryoları dahil.
- Sayaç karşılaştırması: önceki sürümle 6 aynı durum görüntüsü; native artırma ifadeleri değişmedi.
- HD/probe fixture kontrolü: 3 başarılı.
- Lint: 0 hata, {lint.get("Warning",0)} uyarı.
- Debug ve R8/resource shrinking etkin release derlemeleri üretildi.
- Unit sahte-provider çakışması, gerçek WebView kanıtı yerine sunulmadı; aşağıdaki matris gerçek Android Chromium kullanır.

## Android 14 emülatör ölçümleri
HTTPS sayfa/PNG yanıtları anonim ve çevrimdışı fixture'lardır. Gerçek WebView oluşturma, gezinme, DOM okuma ve üretimdeki CDN okuyucusu çalıştırılmıştır. DOM görsel gecikmesi 8 saniye, CDN okuma gecikmesi 1,4 saniyedir. Gerçek Instagram veya SD kart hız testi değildir. PSS, uygulama sürecinin işlem sonu ölçümüdür; ayrı renderer süreçlerinin toplam belleği olarak sunulmaz.

| İstenen | İşçi aralığı (s) | Etkin | Tepe WebView | Tepe hesap | Hesap/dk | PSS MiB | B/A/H |
| --- | --- | --- | --- | --- | --- | --- | --- |
"""
for row in rows:
    text+=f'| {row["requested"]} | {row["minimumDelaySeconds"]} | {row["effective"]} | {row["maxActiveWebViews"]} | {row["maxActiveAccounts"]} | {row["completedPerMinute"]:.2f} | {row["appPssKiB"]/1024:.2f} | {row["success"]}/{row["skipped"]}/{row["errors"]} |\n'
text+=f"""
B/A/H: başarılı/atlanan/hata. Ham hesap başlangıç/bitişleri, CDN eşzamanlılığı, sistem bellek ölçümleri ve süreler Verification.json içindedir.

Aynı on hesaplık koşulda 5 işçi, 1 işçiye göre {speed:.2f} kat hesap/dakika sağladı. Ayrıca 25 saniyelik işçi aralığında beşli senaryo {realistic["maxActiveWebViews"]} gerçek WebView tepesine ulaştı ve işlem aralıkları çakıştı. Bu değerler canlı Instagram garantisi değildir.

## Arka plan
stopWithTask=false, START_STICKY ve kalıcı WorkManager kurtarması eklendi/uyarlandı. Activity durması yalnızca Background Processing kapalıysa kuyruğu duraklatır. Görüntüleyicinin kapatılması toplu işi iptal etmez. Zamanlanmış kurtarma aynı havuzu sınırlı kabul pencereleriyle kullanır; çalışma saatleri ve cooldown korunur.

Android 14'te gerçek foreground servis, gerçek Chromium ve sentetik SAF sağlayıcısıyla Home/görev kaldırma testi geçti. Home öncesi {background["homeBefore"]}, Home sonrası {background["homeAfter"]} yazım görüldü; görev kaldırılmadan önce {background["removeTaskBefore"]} yazım vardı, kuyruk kullanıcı tarafından duraklatılmadan {background["completed"]}/8 dosyayı tamamladı.

## Boyut ve teslimat
- Debug APK: {debug.stat().st_size} bayt.
- Optimize release (imzasız): {release.stat().st_size} bayt.
- 3.2.0 referansı: debug 2.890.417 bayt, imzalı release 690.469 bayt. İmzasız ve imzalı release boyutları aynı ölçüm değildir.
- Kaynak ZIP takip edilen dosyalardan git archive ile üretildi; kişisel JSON, cookie, özel log, imzalama anahtarı, .git ve node_modules içermez.
- İngilizce README ve ayrıntılı docs/FETCHER-040.md kaynak projededir.

Yerel çalışma ortamı bağlantı hatası verdiği için son paketler GitHub doğrulama ortamında hazırlandı. Bu pakette debug APK vardır; yeni güvenli biçimde imzalanmış release APK teslim edilmemektedir. Standart CI debug sertifikası önceki kurulumla farklı olabilir; veri silerek kurulum önerilmez.

## Doğrulama sınırları
Fiziksel Samsung cihazında test yapılmadı. Canlı Instagram oturumuyla hız/HD karşılaştırması yapılmadı. Mevcut HD'nin kullanıcı tarafından doğrulanmış önceki davranışı korunup regresyon fixture'larıyla sınandı. Gerçek süreç öldürme sonrası WorkManager kurtarması, Doze, ekran kapalı uzun süreli çalışma, üretici pil yönetimi ve gerçek SD/cloud SAF sağlayıcıları cihaz üstünde ayrıca doğrulanmalıdır. Force Stop ve Android kota kısıtlamalarının aşılabileceği iddia edilmez.
"""
(destination/"Development-Report-TR.md").write_text(text)
for path in destination.iterdir():
    if path.is_file():
        print("DELIVERY_FILE",path.name,path.stat().st_size,hashlib.sha256(path.read_bytes()).hexdigest())
print("DELIVERY_VERIFICATION",json.dumps(verification))
