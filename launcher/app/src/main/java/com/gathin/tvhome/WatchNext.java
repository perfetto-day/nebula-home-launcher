package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gleb's fork: an app's Continue watching rows from the system's Watch Next table (docs/watch-next.md), and their
 * posters. Needs READ_TV_LISTINGS; without it, or when the app publishes nothing, the list is empty and the row stays
 * hidden. Posters are cached on disk and in memory, decoded down to the card's width.
 */
final class WatchNext {

    static final class Item {
        String title, subtitle;
        String poster, link;
        boolean portrait;
        float progress;
        Intent open;
        long engaged;
    }

    interface Loaded {
        void done(List<Item> items);
    }

    private static final String TAG = "TvHome.WatchNext";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** TvContract's 2:3 and movie-poster aspect ratios: everything else is landscape. */
    private static final int ASPECT_2_3 = 4, ASPECT_MOVIE_POSTER = 5;
    /** A "continue" row this close to the end is finished, however long it lingers in the table. */
    private static final float DONE = 0.97f;

    private WatchNext() {
    }

    /** @param seriesPosters swap each episode still for its show's or film's poster (Cinemeta, by IMDb id) */
    static void load(Context context, String pkg, int max, boolean seriesPosters, Loaded loaded) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            List<Item> items = read(app, pkg, max);
            if (seriesPosters) for (Item item : items) seriesPoster(app, item);
            MAIN.post(() -> loaded.done(items));
        });
    }

    private static List<Item> read(Context context, String pkg, int max) {
        List<Item> items = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException when it passes one.
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (!pkg.equals(str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME))) continue;
                    Item item = item(c);
                    if (item != null) items.add(item);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e.getMessage());
        }
        Collections.sort(items, (a, b) -> Long.compare(b.engaged, a.engaged));
        Log.i(TAG, pkg + ": " + items.size() + " rows");
        return items.size() > max ? new ArrayList<>(items.subList(0, max)) : items;
    }

    private static Item item(Cursor c) {
        Item item = new Item();
        item.title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
        if (item.title == null || item.title.isEmpty()) return null;
        long position = num(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
        long duration = num(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
        item.progress = duration > 0 ? Math.min(1f, position / (float) duration) : 0f;
        if (item.progress >= DONE) return null;
        item.engaged = num(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
        item.poster = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
        int aspect = (int) num(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO);
        item.portrait = aspect == ASPECT_2_3 || aspect == ASPECT_MOVIE_POSTER;
        String season = str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        StringBuilder sub = new StringBuilder();
        if (season != null && !season.isEmpty()) sub.append('S').append(season);
        if (episode != null && !episode.isEmpty()) sub.append(sub.length() > 0 ? " " : "").append('E').append(episode);
        // Some apps write seconds into the millisecond columns; an "episode" under five minutes is not real time.
        if (duration >= 5 * 60_000 && position > 0) {
            long left = Math.max(1, (duration - position) / 60_000);
            if (sub.length() > 0) sub.append(" · ");
            sub.append(left >= 60 ? (left / 60) + " h " + (left % 60) + " min" : left + " min");
        }
        item.subtitle = sub.toString();
        String uri = str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
        item.link = uri;
        if (uri != null) {
            try {
                item.open = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ignored) {
            }
        }
        return item;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
    }

    private static long num(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0;
    }

    // ---- show posters (Cinemeta) ------------------------------------------------------------------

    private static final java.util.regex.Pattern IMDB = java.util.regex.Pattern.compile("tt\\d{5,9}");
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/catalog/";
    private static final String POSTER = "https://images.metahub.space/poster/medium/";

    /**
     * Watch Next carries an episode still; the show's own poster (2:3, with its title art) reads better on Home. The
     * IMDb id comes from the row's link when it has one, else from a Cinemeta search by title, remembered per title.
     * Without an answer the still stays.
     */
    private static void seriesPoster(Context context, Item item) {
        // Nuvio puts the show's own poster (the one its addon shows) in the row's intent: use it as it is.
        String own = item.open != null ? item.open.getStringExtra("poster") : null;
        if (own != null && own.startsWith("http")) {
            item.poster = own;
            item.portrait = true;
            return;
        }
        android.content.SharedPreferences cache = context.getSharedPreferences("cinemeta", Context.MODE_PRIVATE);
        String id = null;
        if (item.link != null) {
            java.util.regex.Matcher m = IMDB.matcher(item.link);
            if (m.find()) id = m.group();
        }
        if (id == null) id = cache.getString(item.title, null);
        if (id == null) {
            id = search(item.title, "series");
            if (id == null) id = search(item.title, "movie");
            if (id != null) cache.edit().putString(item.title, id).apply();
        }
        if (id == null) return;
        item.poster = POSTER + id + "/img";
        item.portrait = true;
    }

    private static String search(String title, String type) {
        HttpURLConnection c = null;
        try {
            String q = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20");
            c = (HttpURLConnection) new URL(CINEMETA + type + "/top/search=" + q + ".json").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                org.json.JSONArray metas = new org.json.JSONObject(buf.toString("UTF-8")).optJSONArray("metas");
                if (metas == null || metas.length() == 0) return null;
                for (int i = 0; i < metas.length(); i++) {
                    org.json.JSONObject meta = metas.getJSONObject(i);
                    if (title.equalsIgnoreCase(meta.optString("name"))) return meta.optString("id", null);
                }
                return metas.getJSONObject(0).optString("id", null);
            }
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            Log.w(TAG, "cinemeta: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- posters ---------------------------------------------------------------------------------

    /** Shows the poster in the view once loaded; the view's tag guards against a recycled request. */
    static void poster(ImageView view, String uri, int widthPx) {
        if (uri == null) return;
        view.setTag(uri);
        Bitmap cached = MEMORY.get(uri);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        Context app = view.getContext().getApplicationContext();
        IO.execute(() -> {
            Bitmap bitmap = decode(app, uri, widthPx);
            if (bitmap == null) return;
            MEMORY.put(uri, bitmap);
            MAIN.post(() -> {
                if (uri.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap decode(Context context, String uri, int widthPx) {
        try {
            File file = uri.startsWith("http") ? download(context, uri) : null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            open(context, uri, file, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= widthPx) opts.inSampleSize *= 2;
            return open(context, uri, file, opts);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "poster: " + e.getMessage());
            return null;
        }
    }

    private static Bitmap open(Context context, String uri, File file, BitmapFactory.Options opts) throws IOException {
        try (InputStream in = file != null ? new java.io.FileInputStream(file)
                : context.getContentResolver().openInputStream(Uri.parse(uri))) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static File download(Context context, String url) throws IOException {
        File dir = new File(context.getCacheDir(), "posters");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        File file = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        if (file.isFile() && file.length() > 0) return file;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        File part = new File(dir, file.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(file)) throw new IOException("cache rename");
        return file;
    }
}
