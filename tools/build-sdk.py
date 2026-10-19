#!/usr/bin/env python3
"""Dependency-free APK fallback using Android SDK tools. Gradle remains the standard build."""
import argparse, pathlib, os, subprocess, zipfile, shutil, tempfile
P=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--release',action='store_true');args=p.parse_args()
sdk=pathlib.Path(os.environ['ANDROID_HOME']);jdk=pathlib.Path(os.environ['JAVA_HOME']);tools=sdk/'build-tools'/'35.0.0';android=sdk/'platforms'/'android-35'/'android.jar'
version=dict(line.split('=',1) for line in (P/'version.properties').read_text().splitlines() if '=' in line)
variant='release' if args.release else 'debug';buildroot=P/'app'/'build'/'sdk';buildroot.mkdir(parents=True,exist_ok=True)
out=pathlib.Path(tempfile.mkdtemp(prefix=variant+'-',dir=buildroot))
def run(*a):subprocess.run([str(x) for x in a],check=True)
manifest=(P/'app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="com.instagram.insfav" ',1).replace('<application ', '<application android:debuggable="'+('false' if args.release else 'true')+'" ',1)
(out/'AndroidManifest.xml').write_text(manifest)
run(tools/'aapt2','compile','--dir',P/'app/src/main/res','-o',out/'res.zip')
run(tools/'aapt2','link','-o',out/'resources.apk','--manifest',out/'AndroidManifest.xml','-I',android,'--java',out/'generated','--min-sdk-version','26','--target-sdk-version','35','--version-code',version['VERSION_CODE'],'--version-name',version['VERSION_NAME']+('' if args.release else '-debug'),'-A',P/'app/src/main/assets',out/'res.zip')
buildconfig=out/'generated/com/instagram/insfav/BuildConfig.java';buildconfig.write_text('package com.instagram.insfav; public final class BuildConfig {public static final boolean DEBUG='+('false' if args.release else 'true')+';}')
classes=out/'classes';classes.mkdir();sources=list((P/'app/src/main/java').rglob('*.java'))+list((out/'generated').rglob('*.java'))
run(jdk/'bin/javac','-source','17','-target','17','-encoding','UTF-8','-classpath',android,'-d',classes,*sources)
classjar=out/'classes.jar'
with zipfile.ZipFile(classjar,'w',zipfile.ZIP_DEFLATED) as z:
 for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes))
dex=out/'dex';dex.mkdir()
if args.release:
 rules=out/'rules.pro';rules.write_text('''-keep public class com.instagram.insfav.MainActivity extends android.app.Activity { public <init>(); }
-keepclassmembers class com.instagram.insfav.MainActivity$Bridge { @android.webkit.JavascriptInterface <methods>; }
-keepattributes *Annotation*
-dontwarn java.lang.invoke.StringConcatFactory
''')
 run(jdk/'bin/java','-cp',tools/'lib/d8.jar','com.android.tools.r8.R8','--release','--lib',android,'--min-api','26','--pg-conf',rules,'--pg-map-output',out/'mapping.txt','--output',dex,classjar)
else:run(jdk/'bin/java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--debug','--lib',android,'--min-api','26','--output',dex,classjar)
unsigned=out/'unsigned.apk';shutil.copy(out/'resources.apk',unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 for f in dex.glob('*.dex'):z.write(f,f.name)
aligned=out/'aligned.apk';run(tools/'zipalign','-f','4',unsigned,aligned)
if args.release:
 keystore=os.environ.get('INSFAV_KEYSTORE');alias=os.environ.get('INSFAV_KEY_ALIAS','insfav');password=os.environ.get('INSFAV_STORE_PASSWORD');keypass=os.environ.get('INSFAV_KEY_PASSWORD',password)
else:
 keystore=str(pathlib.Path.home()/'.android/debug.keystore');alias='androiddebugkey';password=keypass='android'
 if not pathlib.Path(keystore).exists():
  pathlib.Path(keystore).parent.mkdir(parents=True,exist_ok=True)
  run(jdk/'bin/keytool','-genkeypair','-keystore',keystore,'-storepass',password,'-keypass',keypass,'-alias',alias,'-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-keysize','2048','-validity','10000')
final=out/('INSFAV-'+variant+('.apk' if keystore else '-unsigned.apk'))
if keystore:
 env=os.environ.copy();env['SDK_SIGN_STORE_PASS']=password;env['SDK_SIGN_KEY_PASS']=keypass
 subprocess.run([str(tools/'apksigner'),'sign','--ks',str(keystore),'--ks-key-alias',alias,'--ks-pass','env:SDK_SIGN_STORE_PASS','--key-pass','env:SDK_SIGN_KEY_PASS','--out',str(final),str(aligned)],env=env,check=True)
 run(tools/'apksigner','verify','--verbose',final)
else:shutil.copy(aligned,final)
destination=buildroot/variant;destination.mkdir(parents=True,exist_ok=True)
shutil.copy2(final,destination/final.name)
if (out/'mapping.txt').exists():shutil.copy2(out/'mapping.txt',destination/'mapping.txt')
print(destination/final.name)
