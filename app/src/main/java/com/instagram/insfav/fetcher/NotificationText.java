package com.instagram.insfav.fetcher;
import android.content.Context;
import com.instagram.insfav.UiPreferences;
import java.util.*;
import java.util.regex.*;
/** Translate presentation text only; queue state and diagnostics retain their stable codes. */
final class NotificationText {
 private static final Map<String,String> TR=new LinkedHashMap<>();
 private static final Pattern WORDS;
 static{
  String[][] pairs={{"Completed — Retry available","Tamamlandı — Yeniden denenebilir"},{"Completed with failures","Hatalarla tamamlandı"},{"Authentication Required","Oturum Gerekli"},{"Loading saved queue and index","Kayıtlı kuyruk ve indeks yükleniyor"},{"Loading persistent index","Kalıcı indeks yükleniyor"},{"Automatic resume scheduled within working hours; Android may defer","Çalışma saatlerinde devam planlandı; Android geciktirebilir"},{"Resume manually after cooldown","Bekleme bitince elle devam edin"},{"Paused by user; saved progress retained","Kullanıcı duraklattı; ilerleme korundu"},{"Queue and images preserved","Kuyruk ve görseller korundu"},{"saved for manual Retry","elle yeniden denemek için kayıtlı"},{"Cooldown duration unknown","Bekleme süresi bilinmiyor"},{"Instagram rate-limit page","Instagram istek sınırı sayfası"},{"Cooldown until","Bekleme bitişi"},{"Rate Limited","İstek Sınırı"},{"Downloaded","İndirilen"},{"Remaining","Kalan"},{"Existing","Mevcut"},{"Skipped","Atlanan"},{"Failed","Başarısız"},{"Pending","Bekleyen"},{"Completed","Tamamlandı"},{"Interrupted","Kesildi"},{"Downloading","İndiriliyor"},{"Starting","Başlıyor"},{"STARTING","BAŞLIYOR"},{"Indexing","İndeksleniyor"},{"Waiting","Bekleniyor"},{"Paused","Duraklatıldı"},{"Pause","Duraklat"},{"Stop","Durdur"}};
  for(String[] pair:pairs)TR.put(pair[0],pair[1]);
  WORDS=Pattern.compile(String.join("|",TR.keySet().stream().map(Pattern::quote).toArray(String[]::new)));
 }
 static String text(Context c,String source){if(!UiPreferences.language(c).equals("tr"))return source;Matcher m=WORDS.matcher(source);StringBuffer out=new StringBuffer();while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(TR.get(m.group())));m.appendTail(out);return out.toString();}
 private NotificationText(){}
}
