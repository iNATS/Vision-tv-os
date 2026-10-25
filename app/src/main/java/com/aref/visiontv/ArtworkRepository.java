package com.aref.visiontv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches featured artwork and metadata from TMDB for apps that map to a streaming provider.
 * Everything is cached in memory and on disk; callers always receive a main-thread callback.
 */
final class ArtworkRepository {
    private static final String TAG = "VisionTV/Artwork";
    private static final String ATTRIBUTION = "Artwork & data: TMDB";
    private static final long META_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int TARGET_IMAGE_WIDTH = 1920;

    static final class Provider {
        final int tmdbId;
        final String label;
        final String type;

        Provider(int tmdbId, String label, String type) {
            this.tmdbId = tmdbId;
            this.label = label;
            this.type = type;
        }
    }

    interface ContentListener {
        void onContent(int generation, FeaturedContent content);
    }

    interface RecommendationsListener {
        void onRecommendations(int generation, List<Recommendation> items);
    }

    interface ImageListener {
        void onImage(Bitmap bitmap);
    }

    private static final Map<String, Provider> PROVIDERS = new HashMap<>();
    private static final Map<Integer, String> GENRES = new HashMap<>();

    static {
        PROVIDERS.put("com.netflix.ninja", new Provider(8, "Netflix", "movie"));
        PROVIDERS.put("com.amazon.amazonvideo.livingroom", new Provider(9, "Prime Video", "movie"));
        PROVIDERS.put("com.disney.disneyplus", new Provider(337, "Disney+", "movie"));
        PROVIDERS.put("com.apple.atve.androidtv.appletv", new Provider(350, "Apple TV+", "movie"));
        PROVIDERS.put("com.hulu.plus", new Provider(15, "Hulu", "movie"));
        PROVIDERS.put("com.peacocktv.peacockandroid", new Provider(386, "Peacock", "movie"));
        PROVIDERS.put("com.cbs.ott", new Provider(531, "Paramount+", "movie"));
        PROVIDERS.put("com.crunchyroll.crunchyroid", new Provider(283, "Crunchyroll", "tv"));
        PROVIDERS.put("com.wbd.stream", new Provider(1899, "Max", "movie"));

        GENRES.put(28, "Action");
        GENRES.put(12, "Adventure");
        GENRES.put(16, "Animation");
        GENRES.put(35, "Comedy");
        GENRES.put(80, "Crime");
        GENRES.put(99, "Documentary");
        GENRES.put(18, "Drama");
        GENRES.put(10751, "Family");
        GENRES.put(14, "Fantasy");
        GENRES.put(36, "History");
        GENRES.put(27, "Horror");
        GENRES.put(10402, "Music");
        GENRES.put(9648, "Mystery");
        GENRES.put(10749, "Romance");
        GENRES.put(878, "Science Fiction");
        GENRES.put(10770, "TV Movie");
        GENRES.put(53, "Thriller");
        GENRES.put(10752, "War");
        GENRES.put(37, "Western");
        GENRES.put(10759, "Action & Adventure");
        GENRES.put(10762, "Kids");
        GENRES.put(10763, "News");
        GENRES.put(10764, "Reality");
        GENRES.put(10765, "Sci-Fi & Fantasy");
        GENRES.put(10766, "Soap");
        GENRES.put(10767, "Talk");
        GENRES.put(10768, "War & Politics");
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "vision-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory;
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final File cacheDir;
    private final String apiKey;
    private final String region;
    private final AtomicInteger activeRequests = new AtomicInteger();

    ArtworkRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences("vision-home", Context.MODE_PRIVATE);
        this.apiKey = resolveApiKey();
        String configuredRegion = this.preferences.getString("tmdb-region", "");
        this.region = TextUtils.isEmpty(configuredRegion)
                ? (TextUtils.isEmpty(BuildConfig.TMDB_REGION) ? "US" : BuildConfig.TMDB_REGION)
                : configuredRegion;
        this.cacheDir = new File(this.context.getCacheDir(), "artwork");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create artwork cache directory");
        }
        int maxCache = (int) Math.min(Runtime.getRuntime().maxMemory() / 10L, 32L * 1024L * 1024L);
        this.memory = new LruCache<String, Bitmap>(maxCache) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private String resolveApiKey() {
        String stored = preferences.getString("tmdb-key", "");
        if (!TextUtils.isEmpty(stored)) return stored.trim();
        return BuildConfig.TMDB_API_KEY == null ? "" : BuildConfig.TMDB_API_KEY.trim();
    }

    boolean hasApiKey() {
        return !TextUtils.isEmpty(apiKey);
    }

    static boolean supports(String packageName) {
        return PROVIDERS.containsKey(packageName);
    }

    static String providerLabel(String packageName) {
        Provider provider = PROVIDERS.get(packageName);
        return provider == null ? null : provider.label;
    }

    void load(String packageName, int generation, ContentListener listener) {
        if (!supports(packageName) || !hasApiKey()) {
            main.post(() -> listener.onContent(generation, null));
            return;
        }
        io.execute(() -> {
            FeaturedContent content = fetch(packageName, true);
            main.post(() -> listener.onContent(generation, content));
        });
    }

    void prefetch(String packageName) {
        if (!supports(packageName) || !hasApiKey()) return;
        if (!inFlight.add("prefetch:" + packageName)) return;
        io.execute(() -> {
            try {
                fetch(packageName, true);
            } catch (RuntimeException error) {
                Log.w(TAG, "Prefetch failed for " + packageName, error);
            } finally {
                inFlight.remove("prefetch:" + packageName);
            }
        });
    }

    private FeaturedContent fetch(String packageName, boolean allowNetwork) {
        Provider provider = PROVIDERS.get(packageName);
        if (provider == null) return null;
        activeRequests.incrementAndGet();
        try {
            JSONObject metadata = readMetadata(packageName);
            if (metadata == null && allowNetwork) {
                metadata = downloadMetadata(provider, packageName);
            }
            if (metadata == null) return null;
            FeaturedContent content = parse(metadata, provider);
            if (content == null) return null;

            Bitmap bitmap = memory.get(content.imageUrl);
            if (bitmap == null) bitmap = readImage(content.imageUrl);
            if (bitmap == null && allowNetwork) bitmap = downloadImage(content.imageUrl);
            if (bitmap == null) return null;
            memory.put(content.imageUrl, bitmap);
            return content.withBitmap(bitmap, warmDominant(bitmap));
        } catch (Exception error) {
            Log.w(TAG, "Artwork fetch failed for " + packageName, error);
            return null;
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private JSONObject readMetadata(String packageName) {
        File file = metadataFile(packageName);
        if (!file.exists()) return null;
        if (System.currentTimeMillis() - file.lastModified() > META_TTL_MS) return null;
        try {
            return new JSONObject(readFile(file));
        } catch (Exception error) {
            Log.w(TAG, "Corrupt metadata cache for " + packageName);
            file.delete();
            return null;
        }
    }

    private JSONObject downloadMetadata(Provider provider, String packageName) {
        HttpURLConnection connection = null;
        try {
            String request = "https://api.themoviedb.org/3/discover/" + provider.type
                    + "?api_key=" + URLEncoder.encode(apiKey, "UTF-8")
                    + "&language=en-US&sort_by=popularity.desc&include_adult=false"
                    + "&vote_count.gte=40&page=1&with_watch_providers=" + provider.tmdbId
                    + "&watch_region=" + URLEncoder.encode(region, "UTF-8");
            connection = open(request);
            int status = connection.getResponseCode();
            if (status != 200) {
                Log.w(TAG, "TMDB returned " + status + " for " + provider.label);
                return null;
            }
            String body = readStream(connection.getInputStream(), 2 * 1024 * 1024);
            JSONObject json = new JSONObject(body);
            if (json.optJSONArray("results") == null || json.optJSONArray("results").length() == 0) {
                return null;
            }
            writeFile(metadataFile(packageName), body);
            return json;
        } catch (Exception error) {
            Log.w(TAG, "Metadata request failed for " + provider.label + ": " + error.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private FeaturedContent parse(JSONObject json, Provider provider) throws Exception {
        JSONArray results = json.optJSONArray("results");
        if (results == null) return null;
        List<JSONObject> candidates = new ArrayList<>();
        for (int index = 0; index < Math.min(results.length(), 8) && candidates.size() < 5; index++) {
            JSONObject candidate = results.optJSONObject(index);
            if (candidate == null) continue;
            if (TextUtils.isEmpty(candidate.optString("backdrop_path", ""))) continue;
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) return null;
        // Deterministic daily rotation through the top titles for each provider.
        long day = System.currentTimeMillis() / 86400000L;
        JSONObject pick = candidates.get((int) (day % candidates.size()));

        String title = pick.optString("title", pick.optString("name", ""));
        if (TextUtils.isEmpty(title)) return null;
        String date = pick.optString("release_date", pick.optString("first_air_date", ""));
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        double vote = pick.optDouble("vote_average", 0);
        String overview = pick.optString("overview", "").trim();

        List<String> genres = new ArrayList<>();
        JSONArray genreIds = pick.optJSONArray("genre_ids");
        if (genreIds != null) {
            for (int i = 0; i < genreIds.length() && genres.size() < 2; i++) {
                String name = GENRES.get(genreIds.optInt(i));
                if (name != null && !genres.contains(name)) genres.add(name);
            }
        }

        StringBuilder meta = new StringBuilder(provider.label);
        if (!TextUtils.isEmpty(year)) meta.append(" · ").append(year);
        if (vote > 0) meta.append(" · ★ ").append(String.format(Locale.US, "%.1f", vote));
        if (!genres.isEmpty()) meta.append(" · ").append(TextUtils.join(", ", genres));

        String imageUrl = "https://image.tmdb.org/t/p/w1280" + pick.optString("backdrop_path");
        return new FeaturedContent(title, overview, meta.toString(), ATTRIBUTION,
                provider.label, imageUrl, null, 0);
    }

    /** Extracts a warm ambient colour from the artwork so the whole scene can react to it. */
    private static int warmDominant(Bitmap bitmap) {
        int fallback = 0xFF8A6A46;
        if (bitmap == null || bitmap.isRecycled()) return fallback;
        int step = Math.max(1, bitmap.getWidth() / 64);
        long weightSum = 0;
        long rSum = 0;
        long gSum = 0;
        long bSum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < bitmap.getHeight(); y += step) {
            for (int x = 0; x < bitmap.getWidth(); x += step) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 200) continue;
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                Color.RGBToHSV(r, g, b, hsv);
                if (hsv[2] < 0.15f) continue;
                int w = 1 + (int) (hsv[1] * 5);
                weightSum += w;
                rSum += r * w;
                gSum += g * w;
                bSum += b * w;
            }
        }
        if (weightSum == 0) return fallback;
        int r = (int) (rSum / weightSum);
        int g = (int) (gSum / weightSum);
        int b = (int) (bSum / weightSum);
        r = (r * 78 + 227 * 22) / 100;
        g = (g * 78 + 183 * 22) / 100;
        b = (b * 78 + 120 * 22) / 100;
        Color.RGBToHSV(r, g, b, hsv);
        hsv[1] = Math.min(0.72f, Math.max(0.30f, hsv[1] * 0.92f));
        hsv[2] = 0.60f;
        return Color.HSVToColor(hsv);
    }

    void loadRecommendations(String packageName, int generation, RecommendationsListener listener) {
        if (!supports(packageName) || !hasApiKey()) {
            main.post(() -> listener.onRecommendations(generation, new ArrayList<>()));
            return;
        }
        io.execute(() -> {
            List<Recommendation> items = fetchRecommendations(packageName);
            main.post(() -> listener.onRecommendations(generation, items));
        });
    }

    private List<Recommendation> fetchRecommendations(String packageName) {
        Provider provider = PROVIDERS.get(packageName);
        if (provider == null) return new ArrayList<>();
        try {
            JSONObject metadata = readMetadata(packageName);
            if (metadata == null) metadata = downloadMetadata(provider, packageName);
            if (metadata == null) return new ArrayList<>();
            return parseRecommendations(metadata, provider);
        } catch (Exception error) {
            Log.w(TAG, "Recommendations failed for " + packageName, error);
            return new ArrayList<>();
        }
    }

    private List<Recommendation> parseRecommendations(JSONObject json, Provider provider) throws Exception {
        List<Recommendation> items = new ArrayList<>();
        JSONArray results = json.optJSONArray("results");
        if (results == null) return items;
        for (int index = 0; index < results.length() && items.size() < 18; index++) {
            JSONObject entry = results.optJSONObject(index);
            if (entry == null) continue;
            String backdropPath = entry.optString("backdrop_path", "");
            if (TextUtils.isEmpty(backdropPath)) continue;
            String title = entry.optString("title", entry.optString("name", ""));
            if (TextUtils.isEmpty(title)) continue;
            String date = entry.optString("release_date", entry.optString("first_air_date", ""));
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            double vote = entry.optDouble("vote_average", 0);
            List<String> genres = new ArrayList<>();
            JSONArray genreIds = entry.optJSONArray("genre_ids");
            if (genreIds != null) {
                for (int g = 0; g < genreIds.length() && genres.size() < 2; g++) {
                    String name = GENRES.get(genreIds.optInt(g));
                    if (name != null && !genres.contains(name)) genres.add(name);
                }
            }
            String meta = buildMeta(provider.label, year, vote, genres);
            items.add(new Recommendation(
                    title,
                    entry.optString("overview", "").trim(),
                    meta,
                    "https://image.tmdb.org/t/p/w300" + backdropPath,
                    "https://image.tmdb.org/t/p/w1280" + backdropPath,
                    provider.label));
        }
        return items;
    }

    private static String buildMeta(String providerLabel, String year, double vote, List<String> genres) {
        StringBuilder meta = new StringBuilder(providerLabel);
        if (!TextUtils.isEmpty(year)) meta.append(" · ").append(year);
        if (vote > 0) meta.append(" · ★ ").append(String.format(Locale.US, "%.1f", vote));
        if (!genres.isEmpty()) meta.append(" · ").append(TextUtils.join(", ", genres));
        return meta.toString();
    }

    void loadImage(String url, ImageListener listener) {
        if (TextUtils.isEmpty(url)) {
            main.post(() -> listener.onImage(null));
            return;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            main.post(() -> listener.onImage(cached));
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = readImage(url);
            if (bitmap == null) bitmap = downloadImage(url);
            if (bitmap != null) memory.put(url, bitmap);
            Bitmap result = bitmap;
            main.post(() -> listener.onImage(result));
        });
    }

    void prefetchImage(String url) {
        if (TextUtils.isEmpty(url) || memory.get(url) != null) return;
        io.execute(() -> {
            try {
                Bitmap bitmap = readImage(url);
                if (bitmap == null) bitmap = downloadImage(url);
                if (bitmap != null) memory.put(url, bitmap);
            } catch (RuntimeException error) {
                Log.w(TAG, "Image prefetch failed", error);
            }
        });
    }

    static int ambientFor(Bitmap bitmap) {
        return warmDominant(bitmap);
    }

    private Bitmap readImage(String url) {
        File file = imageFile(url);
        if (!file.exists()) return null;
        Bitmap bitmap = decode(file);
        if (bitmap == null) file.delete();
        return bitmap;
    }

    private Bitmap downloadImage(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (connection.getResponseCode() != 200) {
                Log.w(TAG, "Artwork returned " + connection.getResponseCode());
                return null;
            }
            byte[] bytes = readBytes(connection.getInputStream(), MAX_IMAGE_BYTES);
            if (bytes == null || bytes.length == 0) return null;
            File target = imageFile(url);
            File temp = new File(target.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                return null;
            }
            return decode(target);
        } catch (Exception error) {
            Log.w(TAG, "Artwork download failed: " + error.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_IMAGE_WIDTH) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", "VisionTV/2.0 (Android TV)");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private File metadataFile(String packageName) {
        return new File(cacheDir, "meta_" + Integer.toHexString(packageName.hashCode()) + ".json");
    }

    private File imageFile(String url) {
        return new File(cacheDir, "img_" + Integer.toHexString(url.hashCode()) + ".img");
    }

    private static String readFile(File file) throws IOException {
        return new String(readBytes(new java.io.FileInputStream(file), 2 * 1024 * 1024),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readStream(InputStream input, int limit) throws IOException {
        byte[] bytes = readBytes(input, limit);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeFile(File file, String body) {
        try {
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (temp.renameTo(file)) file.setLastModified(System.currentTimeMillis());
        } catch (IOException error) {
            Log.w(TAG, "Unable to cache metadata: " + error.getMessage());
        }
    }

    void shutdown() {
        io.shutdownNow();
        memory.evictAll();
    }
}
