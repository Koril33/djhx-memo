package app.djhx.memo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only; Java keeps the separate provider process independent of target APK Kotlin. */
public final class ExportFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if ("denied.md".equals(name)) throw new SecurityException("fixture denied");
        if (name == null || !name.matches("[a-f0-9-]+\\.md")) throw new IllegalArgumentException("fixture name");
        File directory = new File(getContext().getCacheDir(), "export-documents");
        directory.mkdirs();
        return new File(directory, name);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int flags = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
            : ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE;
        return ParcelFileDescriptor.open(file(uri), flags);
    }
    @Override public String getType(Uri uri) { return "text/markdown"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return file(uri).delete() ? 1 : 0; }
}
