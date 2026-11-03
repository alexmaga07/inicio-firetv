package tv.inicio.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Entrega al instalador de Android el APK descargado por Updater (cache/update/inicio.apk).
 * Desde Android 7 no se pueden pasar rutas file:// a otras apps; esto hace de FileProvider
 * (que no está disponible sin las librerías de soporte). Solo sirve ese archivo y en lectura.
 */
public class UpdateProvider extends ContentProvider {
    static final String AUTHORITY = "tv.inicio.launcher.update";

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"inicio.apk".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "update"), "inicio.apk");
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
