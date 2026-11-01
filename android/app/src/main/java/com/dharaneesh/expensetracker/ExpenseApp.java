package com.dharaneesh.expensetracker;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.work.*;
import com.dharaneesh.expensetracker.data.Repository;
import com.dharaneesh.expensetracker.sync.SyncWorker;
import java.util.concurrent.*;

public class ExpenseApp extends Application {
  public static final ExecutorService IO = Executors.newSingleThreadExecutor();
  public Repository repository;

  @Override
  public void onCreate() {
    super.onCreate();
    repository = new Repository(this);
    WorkManager.getInstance(this)
        .enqueueUniquePeriodicWork(
            "sync",
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(
                    new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build());
  }

  public static Repository repo(Context c) {
    return ((ExpenseApp) c.getApplicationContext()).repository;
  }

  public static void budgetAlert(Context c, String category) {
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "budgets", "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent intent =
        PendingIntent.getActivity(
            c,
            0,
            new Intent(c, com.dharaneesh.expensetracker.ui.MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);
    nm.notify(
        category.hashCode(),
        new Notification.Builder(c, "budgets")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Monthly budget reached")
            .setContentText(category + " has reached its limit.")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build());
  }
}
