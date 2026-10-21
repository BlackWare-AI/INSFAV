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
import re
uiChecks=int(re.search(r"(\d+) UI checks passed",browser).group(1))
assert uiChecks>=78
lint=dict(Counter(item.get("severity") for item in ET.parse(build/"app/build/reports/lint-results-debug.xml").getroot()))
assert not lint.get("Error",0)
shutil.copytree(build/"delivery",destination,dirs_exist_ok=True)
shutil.copytree(proof,destination/"verification/android-14",dirs_exist_ok=True)
shutil.copytree(build/"measurements",destination/"verification/thumbnail-measurements",dirs_exist_ok=True)
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
    "application":"INSFAV","version":"3.4.0","build":6,"sourceInsfavCommit":source,
    "sourceFetcherCommit":"b3f03f2a30c36f89add5e954b9b121a7b9d899ae",
    "preservedInsfavCommit":"ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1",
    "nativeTests":tests,"hostUiChecks":uiChecks,"lint":lint,
    "emulatorSdk":34,"emulatorUsesRealChromium":True,"emulatorUsesOfflineResponses":True,
    "emulatorMatrix":rows,"backgroundFixture":background,
    "speedMultiplierFiveVsOne":speed,"physicalDeviceTested":False,"liveInstagramBenchmark":False,
    "debugApkBytes":debug.stat().st_size,"releaseUnsignedBytes":release.stat().st_size,
    "debugApkSha256":hashlib.sha256(debug.read_bytes()).hexdigest(),
    "hdProbeSha256":hashlib.sha256((root/"app/src/main/assets/profile-probe-hd.js").read_bytes()).hexdigest(),
    "sourceTree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"]).decode().strip()
}
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
# The new task's report supersedes the historical integration narrative above.
benchmark={}
for variant in ["before","after"]:
    suite=ET.parse(build/f"measurements/native-{variant}.xml").getroot()
    output=suite.findtext("system-out","")
    match=re.search(r"THUMBNAIL_BENCHMARK (\{.*\})",output)
    assert match, f"Missing native benchmark {variant}"
    benchmark[variant]=json.loads(match.group(1))
performance=json.loads((build/"measurements/browser-before-after.json").read_text())
verification["thumbnailNativeBenchmark"]=benchmark
verification["thumbnailHostBrowserBenchmark"]=performance
verification["screenOffFixturePassed"]=background["screenWasOff"] and background["screenOffAfter"]>background["screenOffBefore"]
assert verification["screenOffFixturePassed"]
verification["notificationAndFilesAgree"]=background["notificationDownloaded"]==background["validFiles"]==8
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
report=(root/"docs/THUMBNAIL-OPTIMIZATION-TR.md").read_text()
report+="\n\n## Ölçülen sonuçlar\n\nAndroid/Robolectric: **%d test**, hata/başarısız/atlanan **0**. Chromium UI: **%d kontrol**. Lint: **0 hata**, **%d uyarı**.\n"%(tests["tests"],uiChecks,lint.get("Warning",0))
report+="\n### Native kaynak yolu — aynı sentetik test, API 34 Robolectric\n\n| Ölçüm | Önce | Sonra |\n|---|---:|---:|\n"
for key in ["requests","sourceOpens","directoryQueriesDuringReads","elapsedMs","responseBytes","originalBytesUnchanged","derivedCacheBytes","indexBytes","privateFilesBytes","privateCacheBytes","sourceFolderBytes"]:
    report+=f"| {key} | {benchmark['before'][key]} | {benchmark['after'][key]} |\n"
report+="\nBu süreler Robolectric/JVM ölçümüdür; fiziksel SAF gecikmesi veya telefon performansı değildir. Önceki diskteki sıcak kopya okumalarının yerine kaynak okumaları gelir; browser reuse bundan ayrı ölçülür.\n\n### Host Chromium — aynı kaydırma izi ve sentetik 256×256 WebP\n\n| Tekrar | Hesap | Sürüm | İlk açılış ms | FPS | Ortalama/P95/P99 frame ms | >32ms oranı | Tepe satır | HTTP görsel istekleri |\n|---|---|---|---:|---:|---|---:|---:|---:|\n"
for row in performance["samples"]:
    report+=f"| {row.get('attempt',1)} | {row['size']} | {row['variant']} | {row['initialMs']} | {row['fps']:.2f} | {row['frameMeanMs']:.2f} / {row['frameP95Ms']:.2f} / {row['frameP99Ms']:.2f} | {row['jankOver32Ms']:.3f} | {row['maxNodes']} | {row['requests']} |\n"
report+="\nHost Chromium FPS, Android ekran FPS'si değildir. JS heap ölçümü ayrı JSON'da vardır; PSS değildir. Native cache hit yerine eşzamanlı okuma birleştirme, JS browser Image reuse hit/miss ayrı tutulur. Browser'ın dahili decode cache hit oranı ve izole decode süresi: **Not Measured**.\n"
report+=f"\n### Arka plan ve bildirim\n\nAndroid 14 gerçek Chromium/offline fixture: Home {background['homeBefore']} → {background['homeAfter']}; ekran kapalı {background['screenOffBefore']} → {background['screenOffAfter']}; görev kaldırma sonrası {background['completed']}/8. Doğrulanmış kaynak dosyalar {background['validFiles']}; Downloaded {background['notificationDownloaded']}; sonuç bildirimi progress {background['notificationProgress']}.\n"
report+=f"\n### Paket\n\nDebug APK: {debug.stat().st_size} bayt. Optimize imzasız release: {release.stat().st_size} bayt. Kod commit: `{source}`. Kaynak Fetcher: `b3f03f2a30c36f89add5e954b9b121a7b9d899ae`.\n"
(destination/"Development-Report-TR.md").write_text(report)

report+="\n\n## Değiştirilen dosyalar\n\n"
changed=subprocess.check_output(["git","diff","--name-only","54b43f8151ded94ef0b6655c8575d519effe7fb6","HEAD"]).decode().splitlines()
report+="\n".join("- `"+name+"`" for name in changed)+"\n"
(destination/"Development-Report-TR.md").write_text(report)
for path in destination.iterdir():
    if path.is_file():print("DELIVERY_FILE",path.name,path.stat().st_size,hashlib.sha256(path.read_bytes()).hexdigest())
print("DELIVERY_VERIFICATION",json.dumps(verification))
