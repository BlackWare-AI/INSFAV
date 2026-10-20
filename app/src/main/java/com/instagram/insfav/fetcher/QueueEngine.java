package com.instagram.insfav.fetcher;
import android.content.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
/** Coordinator alone owns queue and SAF commits. Independent broker operations overlap. */
final class QueueEngine {
 interface Reporter{void update(String state);}
 final Context context;final SharedPreferences settings;final Reporter reporter;final BooleanSupplier stopped;
 private long lastSignal;private String lastState="";volatile boolean cancelled;volatile String cancelledState="Interrupted; queue preserved";
 QueueEngine(Context c,Reporter r,BooleanSupplier stop){context=c;settings=SessionState.prefs(c);reporter=r;stopped=stop;}
 void cancel(){cancelled=true;FetchBroker.get(context).cancelBulk();}void cancel(String text){cancelledState=text;cancel();}
 boolean interrupted(){return cancelled||stopped.getAsBoolean()||QueueControl.requested||Thread.currentThread().isInterrupted();}
 void state(String value){long now=android.os.SystemClock.elapsedRealtime();if(value.equals(lastState)&&now-lastSignal<250)return;lastState=value;lastSignal=now;QueueControl.phase=value;settings.edit().putString("state",value).apply();reporter.update(value);Fetcher.publish();}
 void run(String action,boolean bounded){
  long started=System.currentTimeMillis(),generation=settings.getLong("datasetGeneration",0);int finished=0,cached=0;FetchBroker broker=FetchBroker.get(context);Map<String,CompletableFuture<byte[]>> flights=new LinkedHashMap<>();boolean refresh=action.equals("refresh_legacy")||action.equals("scheduled")&&settings.getBoolean("refreshLegacy",false)||action.equals("resume")&&settings.getBoolean("refreshLegacy",false);boolean hoursEnded=false;
  settings.edit().putLong("startedAt",started).putBoolean("refreshLegacy",refresh).apply();broker.beginBatch();
  try(QueueStore queue=new QueueStore(context)){
   if(action.equals("missing")){queue.prepareMissing();for(String user:queue.completedImages())if(!Fetcher.host().hasImage(user))queue.requeueLost(user);}
   if(action.equals("retry_skipped"))queue.retrySkipped();if(action.equals("refresh_legacy"))queue.refreshLegacy();
   OutputFolder output=new OutputFolder(context,settings.getString("tree",""));
   ArrayDeque<String> targets=new ArrayDeque<>(queue.eligible());
   while(!interrupted()){
    if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: collections or folder changed");return;}
    if(!settings.getString("blocked","").isEmpty()){for(String user:flights.keySet())queue.mark(user,"RETRY_LATER",settings.getString("blocked",""));state("Paused: "+settings.getString("blocked",""));return;}
    if(!RunPolicy.read(settings).contains(ZonedDateTime.now()))hoursEnded=true;
    if(bounded&&System.currentTimeMillis()-started>=120000)hoursEnded=true;
    while(!hoursEnded&&!targets.isEmpty()&&flights.size()<broker.capacity()&&!interrupted()){
     String user=targets.remove();String filename=Identity.outputFilename(user);boolean validOutput=output.valid(filename);boolean legacy=refresh&&(validOutput&&output.legacy(filename)||Fetcher.host().legacyImage(user));
     if((Fetcher.host().hasImage(user)||validOutput)&&!legacy){if(validOutput)Fetcher.host().imageWritten(user,output.uri(filename),settings.getString("tree",""));queue.mark(user,"EXISTING","LOCAL_IMAGE");state("Checking existing thumbnails");if(bounded&&++cached>=128&&flights.isEmpty())return;continue;}
     QueueControl.current=user;flights.put(user,broker.submit(user,false));state("Downloading");
    }
    if(flights.isEmpty()){
     if(hoursEnded){state("Waiting for working hours / next background window");return;}
     if(targets.isEmpty()){targets.addAll(queue.eligible());if(targets.isEmpty()){settings.edit().putBoolean("armed",false).commit();state("Completed");return;}}
     continue;
    }
    boolean completed=false;
    for(Iterator<Map.Entry<String,CompletableFuture<byte[]>>> it=flights.entrySet().iterator();it.hasNext();){Map.Entry<String,CompletableFuture<byte[]>> f=it.next();if(!f.getValue().isDone())continue;completed=true;String user=f.getKey();byte[] original=null;
     try{
      original=f.getValue().get();byte[] thumbnail=ThumbnailCodec.convert(original);if(interrupted())break;
      if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: data changed");return;}
      android.net.Uri uri=output.commit(Identity.outputFilename(user),thumbnail,refresh);Fetcher.host().imageWritten(user,uri,settings.getString("tree",""));queue.mark(user,"SUCCESS","VALID_WEBP");new Diagnostics(context).log("PROFILE","SUCCESS",0);
     }catch(ExecutionException e){Throwable cause=e.getCause();ProfileProvider.Failure error=cause instanceof ProfileProvider.Failure?(ProfileProvider.Failure)cause:new ProfileProvider.Failure("PROCESSING_ERROR");
      if(error.code.equals("OUTSIDE_WORKING_HOURS")){hoursEnded=true;}
      else if(FailurePolicy.pause(error)){queue.mark(user,"RETRY_LATER",error.code);state("Paused: "+error.code);return;}
      else if(FailurePolicy.unavailable(error))queue.mark(user,"SKIPPED_UNAVAILABLE",error.code);
      else if(FailurePolicy.noImage(error))queue.mark(user,"SKIPPED_NO_IMAGE",error.code);
      else{queue.attempted(user);queue.mark(user,queue.attempts(user)>=3?"FAILED":"RETRY_LATER",error.code);}
      new Diagnostics(context).log("PROFILE",error.code,error.http);
     }catch(CancellationException e){queue.mark(user,"RETRY_LATER","REQUEST_CANCELLED");}
     catch(IOException e){queue.attempted(user);queue.mark(user,queue.attempts(user)>=3?"FAILED":"RETRY_LATER",Diagnostics.imageCode(e));}
     finally{if(original!=null)Arrays.fill(original,(byte)0);it.remove();}
     finished++;long elapsed=Math.max(1,System.currentTimeMillis()-started);QueueControl.completedPerMinute=finished*60000.0/elapsed;QueueControl.etaSeconds=finished>=3?(long)(Math.max(0,queue.pending().size())*60.0/QueueControl.completedPerMinute):-1;state("Downloading");
    }
    if(!completed)Thread.sleep(25);
   }
   state(QueueControl.requested?QueueControl.requestedState:cancelledState);
  }catch(SecurityException e){settings.edit().putString("blocked","STORAGE_PERMISSION_LOST").commit();state("Paused: folder write permission required");}
  catch(Exception|OutOfMemoryError e){settings.edit().putString("blocked","PROCESSING_ERROR").commit();state("Paused: processing error; queue preserved");}
  finally{broker.cancelBulk();broker.endBatch();settings.edit().putLong("elapsedSeconds",settings.getLong("elapsedSeconds",0)+Math.max(0,(System.currentTimeMillis()-started)/1000)).putLong("startedAt",0).apply();QueueControl.current="";QueueControl.etaSeconds=-1;Fetcher.publish();}
 }
}
