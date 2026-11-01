package com.dharaneesh.expensetracker.capture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.*;
import com.dharaneesh.expensetracker.ExpenseApp;

public class NotificationCaptureService extends NotificationListenerService {
  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    if (!sbn.getPackageName().equals("com.google.android.apps.nbu.paisa.user")) return;
    if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
    if (!getSharedPreferences("settings", 0).getBoolean("capture_consent", false)) return;
    Bundle e = sbn.getNotification().extras;
    String title = String.valueOf(e.getCharSequence(Notification.EXTRA_TITLE, ""));
    String text =
        String.valueOf(
            e.getCharSequence(
                Notification.EXTRA_BIG_TEXT, e.getCharSequence(Notification.EXTRA_TEXT, "")));
    ExpenseApp.IO.execute(
        () ->
            ExpenseApp.repo(this)
                .capture(
                    title,
                    text,
                    "gpay",
                    sbn.getPostTime(),
                    sbn.getKey() + ":" + sbn.getPostTime()));
  }
}
