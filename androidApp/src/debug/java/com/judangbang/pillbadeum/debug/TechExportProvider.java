package com.judangbang.pillbadeum.debug;

import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.File;

public final class TechExportProvider extends FileProvider {
    static final String DIRECTORY = "task056-export";

    @Override
    public boolean onCreate() {
        File[] previous = new File(getContext().getCacheDir(), DIRECTORY).listFiles();
        int removed = 0;
        if (previous != null) {
            for (File file : previous) {
                if (file.isFile() && file.delete()) removed++;
            }
        }
        Log.i("TASK056", "COLD_START removed=" + removed);
        return super.onCreate();
    }
}
