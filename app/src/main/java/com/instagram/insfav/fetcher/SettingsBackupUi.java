package com.instagram.insfav.fetcher;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.Executor;
import java.io.*;
/** Passwords stay in native controls and never enter the WebView bridge, logs or diagnostics. */
public final class SettingsBackupUi {
 public interface Host {String export(char[] password)throws Exception;JSONObject inspect(String raw,char[] password)throws Exception;void apply(JSONObject data)throws Exception;void message(String text);}
 private final Activity activity;private final Executor io;private final Host host;private String encryptedExport;
 private static final int SAVE=61,OPEN=62;
 public SettingsBackupUi(Activity a,Executor io,Host host){activity=a;this.io=io;this.host=host;}
 public void start(boolean exporting){activity.runOnUiThread(()->{
  if(exporting)password(true,pass->io.execute(()->{try{encryptedExport=host.export(pass);activity.runOnUiThread(()->activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"INSFAV-settings-encrypted.json"),SAVE));}catch(Exception e){host.message("Settings export failed. Existing settings are unchanged.");}finally{Arrays.fill(pass,'\0');}}));
  else activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),OPEN);
 });}
 private interface PasswordAction{void run(char[] password);}
 private void password(boolean confirm, PasswordAction action) {
  BackupPinUi.show(activity, confirm ? "Export All Settings" : "Import All Settings", confirm, action::run);
 }
 public boolean result(int request,int result,Intent intent){
  if(request!=SAVE&&request!=OPEN)return false;
  if(result!=Activity.RESULT_OK||intent==null||intent.getData()==null){encryptedExport=null;return true;}
  Uri uri=intent.getData();
  if(request==SAVE){String data=encryptedExport;encryptedExport=null;io.execute(()->{try(OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")){if(data==null||out==null)throw new IOException();out.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));host.message("Encrypted settings exported.");}catch(Exception e){host.message("Settings export failed.");}});}
  else password(false,pass->io.execute(()->{try{
   String raw;try(InputStream in=activity.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException();out.write(b,0,n);}raw=out.toString("UTF-8");}
   JSONObject data=host.inspect(raw,pass);host.apply(data);host.message("Settings restored. Retrieval is stopped; review the folder and tap Resume when ready.");
  }catch(Exception e){host.message("Settings import failed: wrong password, incompatible backup or active retrieval. Existing settings were preserved.");}finally{Arrays.fill(pass,'\0');}}));return true;
 }
}
