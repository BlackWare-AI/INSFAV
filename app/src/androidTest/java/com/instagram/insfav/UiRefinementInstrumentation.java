package com.instagram.insfav;
import android.app.Instrumentation;
import android.os.Bundle;
/** Only the changed display/gesture UI; all fixtures are synthetic and restored. */
public final class UiRefinementInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{ScrollDeviceChecks.run(this);result.putString("stream","REFINEMENT_UI_OK\n");finish(android.app.Activity.RESULT_OK,result);}catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(android.app.Activity.RESULT_CANCELED,result);}}
}
