package org.example.task065.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * Emulator fixture. Displays a synthetic draft without a send action or network access.
 *
 * <p>Its only filter is SENDTO mailto, as a mail app whose SENDTO activity is not also a SEND one: the app's
 * ACTION_SEND reaches it through the SENDTO selector. Launched with ACTION_MAIN, it reads the clipboard instead.
 */
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
        if (Intent.ACTION_MAIN.equals(draft.getAction())) return;
        Bundle extras = draft.getExtras();
        String[] to = draft.getStringArrayExtra(Intent.EXTRA_EMAIL);
        String text = draft.getStringExtra(Intent.EXTRA_TEXT);
        ClipData clip = draft.getClipData();
        // Android copies EXTRA_TEXT of a SEND into ClipData; anything more than that text would be an attachment.
        boolean clipIsText = clip == null || (clip.getItemCount() == 1 && clip.getItemAt(0).getUri() == null
                && text != null && text.contentEquals(clip.getItemAt(0).getText()));
        boolean onlyDraft = Intent.ACTION_SEND.equals(draft.getAction()) && draft.getData() == null
                && draft.getType() == null && clipIsText && extras != null && extras.size() == 3
                && to != null && to.length == 1 && extras.containsKey(Intent.EXTRA_SUBJECT) && text != null;
        screen.setText("to: " + (to == null ? "-" : String.join(",", to)) + "\n"
                + draft.getStringExtra(Intent.EXTRA_SUBJECT) + "\n" + text);
        Log.i(TAG, "draft onlyApprovedFields=" + onlyDraft);
        Log.i(TAG, "draft detail action=" + draft.getAction() + " clipItems="
                + (clip == null ? 0 : clip.getItemCount()));
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() == 1) {
                screen.setText("clipboard: " + clip.getItemAt(0).getText());
            }
        }
    }
}
