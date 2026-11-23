package com.judangbang.pillbadeum.debug;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TechActivity extends Activity {
    private static final String TAG = "TASK056";
    private static final String NAME = "가상 엄마";
    private static final String EMAIL = "test@example.invalid";
    private static final String COPIED = "이제 긴급 정보에 붙여 넣어 주세요";
    private static final String FIXTURE =
            "가상 엄마. 알레르기: 가상 물질. 지금 먹는 약: 가상정 10mg 1정 매일 아침. "
            + "보호자: 가상 딸 010-0000-0000. 필받음 건강 카드, 2026-11-23 고침.";
    private static final String NOTE = "사용자가 적은 내용이며 의료기관의 기록이 아니에요";
    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 36;
    private static final int MIN_ROW = 21;
    private static final int TOP = 108;
    private static final int BOTTOM = 74;
    private LinearLayout content;
    private TextView message;
    private boolean showing;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        String mode = getIntent().getStringExtra("mode");
        if ("copy".equals(mode)) {
            text("응급 대비 문장", 22);
            text(FIXTURE, 16);
            button("민감한 내용 복사", () -> copy(FIXTURE, true));
            message = text("", 16);
            text("넣는 곳", 18);
            button("민감 표시 없이 복사", () -> copy("TASK056-PUBLIC-PREVIEW", false));
            button("붙여넣기 시험 197자", () -> copy(lengthFixture(197), true));
            button("붙여넣기 시험 1024자", () -> copy(lengthFixture(1024), true));
        } else if ("export".equals(mode)) {
            text("내보내기", 22);
            button("Android 공유 창 PDF", () -> share("pdf"));
            button("Android 공유 창 PNG", () -> share("png"));
            runExport();
        } else if ("mail".equals(mode)) {
            text("의견 보내기", 22);
            button("메일 앱 열기", this::mail);
            message = text("", 16);
        } else if ("settings".equals(mode)) {
            button("설정", () -> startSettings(Settings.ACTION_SETTINGS));
            button("긴급 설정", () -> startSettings("android.settings.EMERGENCY_SETTINGS"));
            button("의료 정보 편집", () -> startSettings("android.settings.EDIT_EMERGENCY_INFO"));
            button("의료 정보 항목 편집", () -> startSettings("android.emergency.EDIT_MEDICAL_INFO"));
        } else {
            showHome();
        }
    }

    private void showHome() {
        content.removeAllViews();
        text("건강 카드 기술 시험", 22);
        button("보여 주기", this::showCard);
    }

    private void showCard() {
        showing = true;
        updateKeepScreenOn();
        content.removeAllViews();
        text(NAME, 26);
        text("지금 먹는 약", 18);
        text("가상정 10mg 1정 · 매일 아침", 20);
        text("알레르기", 18);
        text("가상 물질", 20);
        text("마지막으로 고친 날 2026-11-23", 14);
        text(NOTE, 14);
        button("닫기", this::closeCard);
    }

    private void updateKeepScreenOn() {
        if (showing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void closeCard() {
        showing = false;
        updateKeepScreenOn();
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (showing) closeCard();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateKeepScreenOn();
    }

    @Override
    protected void onPause() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        content.addView(view);
        return view;
    }

    private void button(String label, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setOnClickListener(ignored -> action.run());
        content.addView(view);
    }

    private void copy(String value, boolean sensitive) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("TASK056", value);
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        ClipData result = clipboard.getPrimaryClip();
        boolean matches = result != null && value.contentEquals(result.getItemAt(0).getText());
        Log.i(TAG, "CLIP match=" + matches + " sensitive=" + (sensitive && Build.VERSION.SDK_INT >= 33)
                + " count=" + value.length());
        message.setText(Build.VERSION.SDK_INT >= 33 ? COPIED : "복사했어요. 긴급 정보에 붙여 넣어 주세요\n"
                + "이 휴대폰에서는 복사한 글이 클립보드 미리보기에 보일 수 있어요");
    }

    private static String lengthFixture(int length) {
        char[] fixture = new char[length];
        Arrays.fill(fixture, 'A');
        fixture[length - 1] = 'Z';
        return new String(fixture);
    }

    private void startSettings(String action) {
        try {
            startActivity(new Intent(action));
            Log.i(TAG, "SETTINGS launched " + action);
        } catch (ActivityNotFoundException noActivity) {
            Log.i(TAG, "SETTINGS unavailable " + action);
        } catch (SecurityException restricted) {
            Log.i(TAG, "SETTINGS restricted " + action);
        }
    }

    private void mail() {
        Intent email = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, "[필받음] 의견")
                .putExtra(Intent.EXTRA_TEXT, "앱 버전: 0.1.0.1 (1)\nAndroid 버전: "
                        + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n기기 모델: "
                        + Build.MANUFACTURER + " " + Build.MODEL);
        Log.i(TAG, "MAIL resolved=" + email.resolveActivity(getPackageManager()));
        try {
            startActivity(email);
            Log.i(TAG, "MAIL started");
        } catch (ActivityNotFoundException noActivity) {
            Log.i(TAG, "MAIL no handler");
            message.setText(EMAIL);
            button("주소 복사", () -> copy(EMAIL, false));
        }
    }

    private void runExport() {
        try {
            File cache = new File(getCacheDir(), TechExportProvider.DIRECTORY);
            if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("cache");
            render(cache, "gothic", Typeface.createFromAsset(getAssets(), "GothicA1-Regular.ttf"));
            render(cache, "system", Typeface.create("sans-serif", Typeface.NORMAL));
            Log.i(TAG, "EXPORT complete fontScale=" + getResources().getConfiguration().fontScale);
        } catch (IOException error) {
            throw new IllegalStateException("Export probe failed", error);
        }
    }

    private void render(File cache, String font, Typeface typeface) throws IOException {
        TextPaint body = paint(typeface, 12);
        TextPaint title = paint(typeface, 18);
        List<StaticLayout> shortCard = blockLayouts(body, 5);
        List<StaticLayout> longCard = blockLayouts(body, 60);
        pdf(new File(cache, font + "-one.pdf"), shortCard, body, title);
        pdf(new File(cache, font + "-two.pdf"), longCard, body, title);
        png(new File(cache, font + "-one.png"), shortCard, body, title);
        png(new File(cache, font + "-long.png"), longCard, body, title);
        if ("system".equals(font)) {
            pdf(new File(cache, "pillbadeum-card-20261123.pdf"), shortCard, body, title);
            png(new File(cache, "pillbadeum-card-20261123.png"), shortCard, body, title);
        }
        Log.i(TAG, "EXPORT font=" + font + " KoreanGlyphs=" + title.hasGlyph("엄")
                + "/" + body.hasGlyph("약") + " bodyPt=" + body.getTextSize() + " titlePt=" + title.getTextSize());
    }

    private static TextPaint paint(Typeface face, int size) {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTypeface(face);
        paint.setTextSize(size);
        return paint;
    }

    private static List<StaticLayout> blockLayouts(TextPaint paint, int count) {
        List<StaticLayout> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String value = i + ". " + (i < 3 ? "지금 먹는 약 · 가상정 10mg 1정" : "알레르기 · 가상 항목");
            rows.add(StaticLayout.Builder.obtain(value, 0, value.length(), paint, WIDTH - 2 * MARGIN)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build());
        }
        return rows;
    }

    private static int rowHeight(StaticLayout row) {
        return Math.max(MIN_ROW, row.getHeight() + 2);
    }

    private void pdf(File file, List<StaticLayout> rows, TextPaint body, TextPaint title) throws IOException {
        List<List<StaticLayout>> pages = new ArrayList<>();
        List<StaticLayout> current = new ArrayList<>();
        pages.add(current);
        int cursor = TOP;
        for (StaticLayout row : rows) {
            if (cursor + rowHeight(row) > HEIGHT - BOTTOM) {
                current = new ArrayList<>();
                pages.add(current);
                cursor = TOP;
            }
            current.add(row);
            cursor += rowHeight(row);
        }
        PdfDocument document = new PdfDocument();
        try (FileOutputStream output = new FileOutputStream(file)) {
            for (int i = 0; i < pages.size(); i++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, i + 1).create());
                String number = pages.size() > 1 ? " · " + (i + 1) + "/" + pages.size() + "쪽" : "";
                try {
                    draw(page.getCanvas(), HEIGHT, pages.get(i), number, body, title);
                } finally {
                    document.finishPage(page);
                }
            }
            document.writeTo(output);
        } finally {
            document.close();
        }
        Log.i(TAG, "PDF " + file.getName() + " pages=" + pages.size() + " pt=" + WIDTH + "x" + HEIGHT);
    }

    private void png(File file, List<StaticLayout> rows, TextPaint body, TextPaint title) throws IOException {
        int bodyHeight = rows.stream().mapToInt(TechActivity::rowHeight).sum();
        int height = Math.max(HEIGHT, TOP + bodyHeight + BOTTOM);
        Bitmap image = Bitmap.createBitmap(WIDTH * 2, height * 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(file)) {
            Canvas canvas = new Canvas(image);
            canvas.scale(2, 2);
            draw(canvas, height, rows, "", body, title);
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG");
            Log.i(TAG, "PNG " + file.getName() + " px=" + image.getWidth() + "x" + image.getHeight());
        } finally {
            image.recycle();
        }
    }

    private void draw(Canvas canvas, int height, List<StaticLayout> rows, String number,
                      TextPaint body, TextPaint title) {
        canvas.drawColor(Color.WHITE);
        canvas.drawText(NAME + number, MARGIN, 48, title);
        canvas.drawText("만든 날짜 2026-11-23", MARGIN, 72, body);
        int y = TOP;
        for (StaticLayout row : rows) {
            canvas.save();
            canvas.translate(MARGIN, y);
            row.draw(canvas);
            canvas.restore();
            y += rowHeight(row);
        }
        canvas.drawText("마지막으로 고친 날짜 2026-11-22", MARGIN, height - 47, body);
        canvas.drawText(NOTE, MARGIN, height - 28, body);
    }

    private void share(String extension) {
        File file = new File(new File(getCacheDir(), TechExportProvider.DIRECTORY),
                "pillbadeum-card-20261123." + extension);
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".task056", file);
        Intent send = new Intent(Intent.ACTION_SEND).setType("pdf".equals(extension) ? "application/pdf" : "image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("Export", uri));
        startActivity(Intent.createChooser(send, "TASK056 share"));
    }
}
