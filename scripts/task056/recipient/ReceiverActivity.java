package org.example.task056.recipient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ReceiverActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String result = Intent.ACTION_SENDTO.equals(intent.getAction()) ? inspectMail(intent) : inspectShare(intent);
        Log.i("TASK056", result);
        TextView view = new TextView(this);
        view.setText(result);
        view.setTextSize(18);
        setContentView(view);
    }

    private static String inspectMail(Intent intent) {
        String expected = "앱 버전: 0.1.0.1 (1)\nAndroid 버전: " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n기기 모델: " + Build.MANUFACTURER + " " + Build.MODEL;
        return "RECIPIENT mail to=" + Uri.parse("mailto:test@example.invalid").equals(intent.getData())
                + " subject=" + "[필받음] 의견".equals(intent.getStringExtra(Intent.EXTRA_SUBJECT))
                + " body=" + expected.equals(intent.getStringExtra(Intent.EXTRA_TEXT));
    }

    private String inspectShare(Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return "RECIPIENT share no uri";
        String extension = "application/pdf".equals(intent.getType()) ? "pdf" : "png";
        boolean read = false;
        boolean writeDenied = false;
        try (DataInputStream stream = new DataInputStream(getContentResolver().openInputStream(uri))) {
            byte[] signature = new byte[4];
            stream.readFully(signature);
            read = "pdf".equals(extension)
                    ? "%PDF".equals(new String(signature, StandardCharsets.US_ASCII))
                    : signature[0] == (byte) 137
                        && signature[1] == 'P' && signature[2] == 'N' && signature[3] == 'G';
        } catch (IOException | SecurityException ignored) {
            // Results are reported as booleans without logging the synthetic payload.
        }
        try (android.os.ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "rw")) {
            // A writable descriptor would fail this probe. No bytes are written.
        } catch (SecurityException expected) {
            writeDenied = true;
        } catch (IOException ignored) {
            // A general error does not prove enforcement of a read-only grant.
        }
        return "RECIPIENT share " + extension + " read=" + read + " writeDenied=" + writeDenied
                + " content=" + "content".equals(uri.getScheme())
                + " filename=" + ("pillbadeum-card-20261123." + extension).equals(uri.getLastPathSegment());
    }
}
