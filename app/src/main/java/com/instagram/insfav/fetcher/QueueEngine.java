package com.instagram.insfav.fetcher;
import android.content.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
/** The original service/scheduler uses a single shared priority broker instead of owning a second provider. */
final class QueueEngine {
 interface Reporter{void update(String state);}
 final Context context;final SharedPreferences settings;final Reporter reporter;final BooleanSupplier stopped;
 volatile boolean cancelled;volatile String cancelledState="Interrupted; queue preserved";
 QueueEngine(Context c,Reporter r,BooleanSupplier stop){context=c;settings=SessionState.prefs(c);reporter=r;stopped=stop;}
 void cancel(){cancelled=true;FetchBroker.get(context).cancelBulk();}
 void cancel(String text){cancelledState=text;cancel();}
 boolean interrupted(){return cancelled||stopped.getAsBoolean()||QueueControl.requested||Thread.currentThread().isInterrupted();}
 void state(String value){QueueControl.phase=value;settings.edit().putString("state",value).apply();reporter.update(value);Fetcher.publish();}
 void run(String action,boolean single){
  long started=android.os.SystemClock.elapsedRealtime();int done=0;long generation=settings.getLong("datasetGeneration",0);
  try(QueueStore queue=new QueueStore(context)){
   if(action.equals("missing")){queue.prepareMissing();for(String user:queue.completedImages())if(!Fetcher.host().hasImage(user)){queue.requeueLost(user);}}
   OutputFolder output=new OutputFolder(context,settings.getString("tree",""));
   while(!interrupted()){
    if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: collections or folder changed");return;}
    if(!settings.getString("blocked","").isEmpty()){state("Paused: "+settings.getString("blocked",""));return;}
    if(!RunPolicy.read(settings).contains(ZonedDateTime.now())){state("Waiting for working hours");return;}
    List<String> users=queue.eligible();if(users.isEmpty()){settings.edit().putBoolean("armed",false).commit();state("Completed");return;}
    String user=users.get(0);QueueControl.current=user;
    if(Fetcher.host().hasImage(user)||output.valid(Identity.outputFilename(user))){
     if(output.valid(Identity.outputFilename(user)))Fetcher.host().imageWritten(user,output.uri(Identity.outputFilename(user)),settings.getString("tree",""));queue.mark(user,"EXISTING","LOCAL_IMAGE");state("Existing local image");if(single)return;continue;}
    state("Waiting / downloading");
    CompletableFuture<byte[]> future=FetchBroker.get(context).submit(user,false);
    byte[] original=null;
    try{
     while(!interrupted())try{original=future.get(250,TimeUnit.MILLISECONDS);break;}catch(TimeoutException waiting){}
     if(interrupted()){future.cancel(true);break;}
     byte[] thumbnail=ThumbnailCodec.convert(original);
     if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: data changed");return;}
     state("Writing thumbnail");
     android.net.Uri uri=output.commit(Identity.outputFilename(user),thumbnail,false);
     Fetcher.host().imageWritten(user,uri,settings.getString("tree",""));queue.mark(user,"SUCCESS","VALID_WEBP");
     new Diagnostics(context).log("PROFILE","SUCCESS",0);
    }catch(ExecutionException problem){
     Throwable cause=problem.getCause();ProfileProvider.Failure error=cause instanceof ProfileProvider.Failure?(ProfileProvider.Failure)cause:new ProfileProvider.Failure("PROCESSING_ERROR");
     if(interrupted())break;
     if(error.code.equals("OUTSIDE_WORKING_HOURS")){state("Waiting for working hours");return;}
     queue.attempted(user);
     if(FailurePolicy.unavailable(error))queue.mark(user,"SKIPPED_UNAVAILABLE",error.code);
     else if(FailurePolicy.pause(error)){queue.mark(user,"RETRY_LATER",error.code);state("Paused: "+error.code);return;}
     else queue.mark(user,queue.attempts(user)>=3?"FAILED":"RETRY_LATER",error.code);
     new Diagnostics(context).log("PROFILE",error.code,error.http);
    }catch(CancellationException error){if(interrupted())break;queue.mark(user,"RETRY_LATER","REQUEST_CANCELLED");}
    catch(IOException error){queue.attempted(user);queue.mark(user,queue.attempts(user)>=3?"FAILED":"RETRY_LATER",Diagnostics.imageCode(error));}
    finally{if(original!=null)Arrays.fill(original,(byte)0);Fetcher.publish();}
    done++;long elapsed=android.os.SystemClock.elapsedRealtime()-started;QueueControl.etaSeconds=done>=3?queue.pending().size()*(elapsed/done)/1000:-1;
    if(single)return;
   }
   state(QueueControl.requested?QueueControl.requestedState:cancelledState);
  }catch(SecurityException error){settings.edit().putString("blocked","STORAGE_PERMISSION_LOST").commit();state("Paused: folder write permission required");}
  catch(Exception|OutOfMemoryError error){settings.edit().putString("blocked","PROCESSING_ERROR").commit();state("Paused: processing error; queue preserved");}
  finally{settings.edit().putLong("elapsedSeconds",settings.getLong("elapsedSeconds",0)+(android.os.SystemClock.elapsedRealtime()-started)/1000).apply();QueueControl.current="";QueueControl.etaSeconds=-1;Fetcher.publish();}
 }
}
