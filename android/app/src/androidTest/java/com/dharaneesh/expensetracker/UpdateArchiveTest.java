package com.dharaneesh.expensetracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dharaneesh.expensetracker.update.AppUpdater;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses only APK fixtures; never reads or changes the app's financial records. */
@RunWith(AndroidJUnit4.class)
public class UpdateArchiveTest {
  private File fixture(String name){
    org.junit.Assume.assumeTrue(BuildConfig.FLAVOR.equals("qa")&&InstrumentationRegistry.getArguments().getString("archiveTest","false").equals("true"));
    return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("updater-tests"),name);
  }
  @Test public void acceptsNewerApkSignedByCurrentKey()throws Exception{
    AppUpdater.verifyArchive(InstrumentationRegistry.getInstrumentation().getTargetContext(),fixture("candidate.apk"),101);
  }
  @Test public void rejectsProdApkInQaApp(){
    assertThrows(Exception.class,()->AppUpdater.verifyArchive(InstrumentationRegistry.getInstrumentation().getTargetContext(),fixture("wrong-channel.apk"),100));
  }
  @Test public void rejectsSameVersionApk(){
    assertThrows(Exception.class,()->AppUpdater.verifyArchive(InstrumentationRegistry.getInstrumentation().getTargetContext(),fixture("baseline.apk"),100));
  }
}
