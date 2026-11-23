package org.example.task065.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Emulator fixture. Displays a synthetic draft without a send action or network access. */
public final class FeedbackReceiver extends Activity {
    private static final String TAG = "TASK065Fixture";
    private TextView screen;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = new TextView(this);
        screen.setPadding(24, 24, 24, 24);
        screen.setTextSize(18);
        setContentView(screen);
        Intent draft = getIntent();
        if (Intent.ACTION_SENDTO.equals(draft.getAction())) {
            Bundle extras = draft.getExtras();
            boolean onlyDraft = draft.getClipData() == null && extras != null && extras.size() == 2
                    && extras.containsKey(Intent.EXTRA_SUBJECT) && extras.containsKey(Intent.EXTRA_TEXT);
            screen.setText(draft.getDataString() + "\n" + draft.getStringExtra(Intent.EXTRA_SUBJECT) + "\n"
                    + draft.getStringExtra(Intent.EXTRA_TEXT));
            Log.i(TAG, "draft onlyApprovedFields=" + onlyDraft);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !Intent.ACTION_SENDTO.equals(getIntent().getAction())) {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() == 1) {
                screen.setText("clipboard: " + clip.getItemAt(0).getText());
            }
        }
    }
}
