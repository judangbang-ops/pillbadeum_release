package org.example.task064.fixture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class SaveCardFile extends Activity {
    private Uri source;
    private TextView status;
    private int reads;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        source = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        layout.addView(status);
        Button read = new Button(this);
        read.setText("Read shared PDF");
        read.setAllCaps(false);
        read.setOnClickListener(view -> inspect());
        layout.addView(read);
        Button save = new Button(this);
        save.setText("Save in Files");
        save.setAllCaps(false);
        save.setOnClickListener(view -> save());
        layout.addView(save);
        setContentView(layout);
        inspect();
    }

    private void inspect() {
        boolean pdf = false;
        boolean writeDenied = false;
        try (InputStream stream = getContentResolver().openInputStream(source)) {
            byte[] signature = new byte[4];
            pdf = stream != null && stream.read(signature) == signature.length
                    && "%PDF".equals(new String(signature, StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            // A failed read is reported below.
        }
        try (ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(source, "rw")) {
            // Only a SecurityException proves that the grant excludes writing.
        } catch (SecurityException expected) {
            writeDenied = true;
        } catch (Exception ignored) {
            // A general failure does not prove that the grant excludes writing.
        }
        status.setText("PDF read=" + pdf + " writeDenied=" + writeDenied + " attempts=" + ++reads);
    }

    private void save() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, source.getLastPathSegment());
        Uri downloads = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null) return;
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            status.setText("PDF saved in Files");
        } catch (Exception e) {
            status.setText("PDF save failed");
        }
    }
}
