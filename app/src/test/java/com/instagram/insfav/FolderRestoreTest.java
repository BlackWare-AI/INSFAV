package com.instagram.insfav;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FolderRestoreTest {
 @Test public void restoringADifferentDirectoryUnderTheSameTreeInvalidatesMetadata() throws Exception {
  LocalImagesTest harness=new LocalImagesTest();harness.setup();Store store=ReflectionHelpers.getField(harness,"store");LocalImages images=ReflectionHelpers.getField(harness,"images");
  images.select(android.net.Uri.parse("content://insfav.test/tree/root"),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
  images.scan((e,v)->{});assertTrue(store.index.getJSONObject("profiles").length()>0);String original=store.original();
  MainActivity.restoreFolderSelection(store,new JSONObject().put("treeUri",store.settings.getString("treeUri")).put("directoryId","root/another"));
  assertFalse(store.index.has("profiles"));assertEquals(original,store.original());
 }
}
