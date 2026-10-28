package com.tvhome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Tries to catch the TV's hardware app-button broadcasts (Netflix / YouTube / Prime)
 * so they keep working without Google's launcher installed.
 */
public class KeyBridgeReceiver extends BroadcastReceiver {

    private static final String TAG = "TVHOME_KEY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = String.valueOf(intent.getAction());
        Bundle extras = intent.getExtras();
        Log.i(TAG, "action=" + action
                + " extras=" + (extras == null ? "none" : extras.toString()));
        Log.i(TAG, "actionOnly=" + action);

        String lower = action.toLowerCase();
        String target = null;
        if (lower.contains("netflix")) {
            target = "com.netflix.ninja";
        } else if (lower.contains("youtube")) {
            target = "com.google.android.youtube.tv";
        } else if (lower.contains("prime") || lower.contains("amazon")) {
            target = "com.amazon.amazonvideo.livingroom";
        }
        if (target == null) {
            return;
        }

        Intent launch = context.getPackageManager().getLeanbackLaunchIntentForPackage(target);
        if (launch == null) {
            launch = context.getPackageManager().getLaunchIntentForPackage(target);
        }
        if (launch == null) {
            Log.w(TAG, "no launch intent for " + target);
            return;
        }
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
            Log.i(TAG, "launched " + target);
        } catch (Exception e) {
            Log.w(TAG, "launch failed for " + target, e);
        }
    }
}
