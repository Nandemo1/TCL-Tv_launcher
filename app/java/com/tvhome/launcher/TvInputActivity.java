package com.tvhome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.media.tv.TvView;

/** Full screen live view of a TV input (HDMI 1, HDMI 2, antenna...). BACK returns home. */
public class TvInputActivity extends Activity {

    public static final String EXTRA_INPUT_ID = "input_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String inputId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INPUT_ID);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        TvView tvView = new TvView(this);
        root.addView(tvView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("Press BACK to return to TV Home");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(12f);
        hint.setPadding(24, 16, 24, 16);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = 8;
        hintParams.leftMargin = 8;
        root.addView(hint, hintParams);

        setContentView(root);

        if (inputId != null && !inputId.isEmpty()) {
            try {
                // Passthrough inputs need this exact channel URI: with Uri.EMPTY the TCL
                // service logs "channelUri is error" and shows a black screen.
                android.net.Uri channelUri =
                        android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId);
                tvView.tune(inputId, channelUri, null);
                hint.setText("Press BACK to return to TV Home");
            } catch (Exception e) {
                hint.setText("Can't switch to this input: " + e.getMessage());
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Return to TV Home, not to whatever app happened to be behind this screen.
        Intent home = new Intent(this, HomeActivity.class);
        home.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }
}
