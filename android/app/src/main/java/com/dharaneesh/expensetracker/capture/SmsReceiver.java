package com.dharaneesh.expensetracker.capture;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import com.dharaneesh.expensetracker.ExpenseApp;

public class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
    if (!context.getSharedPreferences("settings", 0).getBoolean("capture_consent", false)) return;
    SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    if (parts.length == 0) return;
    StringBuilder text = new StringBuilder();
    for (SmsMessage part : parts) text.append(part.getMessageBody());
    long time = parts[0].getTimestampMillis();
    String sender = parts[0].getOriginatingAddress();
    PendingResult result = goAsync();
    ExpenseApp.IO.execute(
        () -> {
          try {
            ExpenseApp.repo(context)
                .capture(
                    sender == null ? "" : sender,
                    text.toString(),
                    "sms",
                    time,
                    "sms:" + sender + ":" + time);
          } finally {
            result.finish();
          }
        });
  }
}
