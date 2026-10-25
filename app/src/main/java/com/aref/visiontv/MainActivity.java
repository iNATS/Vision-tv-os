package com.aref.visiontv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int TEXT_PRIMARY = 0xFFFFF8F0;
    private static final int TEXT_SECONDARY = 0xB8F5EBDD;
    private static final int TEXT_TERTIARY = 0x80EDE0CC;
    private static final int TEXT_CHAMPAGNE = 0xCCE8D5B8;
    private static final int ICON_SURFACE = 0xFF12100E;
    private static final int ICON_BORDER = 0x1FFFF2DE;
    private static final int FOCUS_RING = 0xF2FFF4E2;
    private static final int PILL_IDLE = 0x16FFF3E3;
    private static final int PILL_BORDER = 0x24FFEBD2;
    private static final int DOCK_SURFACE = 0x1CFFF1DC;
    private static final int DOCK_BORDER = 0x38FFE8CC;
    private static final int WARM_AMBER = 0xFFE3B778;

    // tvOS reference proportions (measured from the 1303x785 official home screenshot).
    private static final int TILE_W = 122;
    private static final int TILE_H = 88;
    private static final int TILE_RADIUS = 14;
    private static final int TILE_GAP = 24;
    private static final int BAR_RADIUS = 32;
    private static final int WALLPAPER_ACCENT = 0xFF3B7A8C;

    private final ArrayList<AppEntry> dockApps = new ArrayList<>();
    private final ArrayList<AppEntry> allApps = new ArrayList<>();

    private RecyclerView dock;
    private RecyclerView appsRow;
    private TextView clock;
    private TextView badge;
    private TextView title;
    private TextView meta;
    private TextView overview;
    private TextView attribution;
    private LinearLayout metadata;
    private LinearLayout recSection;
    private RecyclerView recRow;
    private TextView recLabel;
    private final ArrayList<Recommendation> recommendations = new ArrayList<>();
    private RecommendationsAdapter recAdapter;
    private CinematicBackdrop backdrop;
    private ArtworkRepository repository;
    private SharedPreferences preferences;
    private PackageManager packageManager;
    private AppEntry featured;
    private AppEntry focusedApp;
    private int focusGeneration;
    private int focusRequest;
    private int contentGeneration;
    private final Set<String> sceneLoading = new HashSet<>();
    private float focusOffset;
    private int animationMs = 230;
    private boolean reduceMotion;

    private final OvershootInterpolator focusInterpolator = new OvershootInterpolator(0.9f);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        preferences = getSharedPreferences("vision-home", MODE_PRIVATE);
        packageManager = getPackageManager();
        applyConfigurationExtras();
        reduceMotion = Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        if (reduceMotion) animationMs = 0;
        repository = new ArtworkRepository(this);
        loadApps();
        setContentView(buildScene());
        preloadScenes();
    }

    /** Loads each app's leanback banner off-thread; used as full-screen fallback artwork. */
    private void preloadScenes() {
        Thread loader = new Thread(() -> {
            for (AppEntry entry : dockApps) sceneFor(entry);
            for (AppEntry entry : allApps) sceneFor(entry);
        }, "vision-scenes");
        loader.setDaemon(true);
        loader.start();
    }

    private Bitmap sceneFor(AppEntry entry) {
        if (entry.scene != null || sceneLoading.contains(entry.packageName)) return entry.scene;
        sceneLoading.add(entry.packageName);
        try {
            if (packageManager == null) return null;
            ApplicationInfo info = packageManager.getApplicationInfo(entry.packageName, 0);
            if (info.banner != 0) {
                Drawable banner = packageManager
                        .getResourcesForApplication(info).getDrawable(info.banner);
                if (banner != null) {
                    Bitmap scene = drawableToBitmap(banner, 960, 540);
                    if (scene != null) entry.scene = scene;
                    return scene;
                }
            }
        } catch (Exception ignored) {
            // Banner unavailable; the accent bloom with ghost icon still covers the scene.
        } finally {
            sceneLoading.remove(entry.packageName);
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        if (drawable == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable error) {
            return null;
        }
    }

    private void applyConfigurationExtras() {
        Intent intent = getIntent();
        if (intent == null) return;
        String key = intent.getStringExtra("tmdb_key");
        if (!TextUtils.isEmpty(key)) {
            preferences.edit().putString("tmdb-key", key.trim()).apply();
        }
        String region = intent.getStringExtra("tmdb_region");
        if (!TextUtils.isEmpty(region)) {
            preferences.edit().putString("tmdb-region", region.trim().toUpperCase(Locale.US)).apply();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dock != null) dock.post(this::restoreFocus);
        if (backdrop != null) backdrop.setMotionEnabled(!reduceMotion);
    }

    @Override
    public void onDestroy() {
        if (repository != null) repository.shutdown();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (recRow != null && recRow.hasFocus()) {
            focusDock(0);
            return;
        }
        if (appsRow != null && appsRow.hasFocus()) {
            focusDock(0);
            return;
        }
        // tvOS keeps the Home screen in place on Back; it does not exit to another launcher.
    }

    private View buildScene() {
        FrameLayout scene = new FrameLayout(this);
        scene.setBackgroundColor(Color.BLACK);
        backdrop = new CinematicBackdrop(this);
        scene.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), dp(24), dp(36), dp(24));
        scene.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        metadata.setClipChildren(false);
        metadata.setAlpha(0f);
        root.addView(metadata, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        badge = text("", 9, TEXT_CHAMPAGNE, MEDIUM);
        badge.setLetterSpacing(0.16f);
        badge.setSingleLine(true);
        metadata.addView(badge);

        title = text("", 26, TEXT_PRIMARY, MEDIUM);
        title.setLetterSpacing(-0.02f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(20f, 0f, 4f, 0x99000000);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        metadata.addView(title, titleParams);

        meta = text("", 11, TEXT_SECONDARY, NORMAL);
        meta.setLetterSpacing(0.02f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(4);
        metadata.addView(meta, metaParams);

        overview = text("", 11, 0xD9F4E9D9, NORMAL);
        overview.setLetterSpacing(0.005f);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0f, 1.12f);
        overview.setMaxWidth(dp(560));
        overview.setShadowLayer(16f, 0f, 3f, 0x99000000);
        LinearLayout.LayoutParams overviewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overviewParams.topMargin = dp(4);
        metadata.addView(overview, overviewParams);

        attribution = text("", 8, 0x66E9DAC3, NORMAL);
        attribution.setLetterSpacing(0.08f);
        attribution.setSingleLine(true);
        attribution.setVisibility(View.GONE);
        LinearLayout.LayoutParams attributionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attributionParams.topMargin = dp(3);
        metadata.addView(attribution, attributionParams);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout barBand = new LinearLayout(this);
        barBand.setGravity(Gravity.CENTER);
        barBand.setClipChildren(false);
        barBand.setClipToPadding(false);
        barBand.setPadding(dp(6), dp(8), dp(6), dp(8));
        barBand.setBackground(glass(0x33000000, BAR_RADIUS, 0x30FFFFFF));
        barBand.setElevation(dp(10));

        dock = new RecyclerView(this);
        dock.setClipToPadding(false);
        dock.setClipChildren(false);
        dock.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dock.setVerticalScrollBarEnabled(false);
        dock.setHorizontalScrollBarEnabled(false);
        dock.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        dock.setAdapter(new AppsAdapter(dockApps, true));
        dock.setItemAnimator(null);
        barBand.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(TILE_H)));
        root.addView(barBand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H + 16)));

        appsRow = new RecyclerView(this);
        appsRow.setClipToPadding(false);
        appsRow.setClipChildren(false);
        appsRow.setPadding(0, dp(8), 0, dp(8));
        appsRow.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        appsRow.setAdapter(new AppsAdapter(allApps, false));
        appsRow.setItemAnimator(null);
        appsRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appsRow.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H + 16));
        rowParams.topMargin = dp(6);
        root.addView(appsRow, rowParams);

        // Recommendations stay wired but are deferred to a later stage.
        recSection = new LinearLayout(this);
        recSection.setOrientation(LinearLayout.VERTICAL);
        recSection.setVisibility(View.GONE);
        recRow = new RecyclerView(this);
        recRow.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        recAdapter = new RecommendationsAdapter();
        recRow.setAdapter(recAdapter);
        recLabel = text("", 9, TEXT_CHAMPAGNE, MEDIUM);

        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.HORIZONTAL);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(12), 0, dp(4), 0);
        profile.setBackground(glass(0x40000000, 17, 0x30FFFFFF));
        profile.setElevation(dp(8));
        profile.setFocusable(true);
        profile.setContentDescription("Time and profile");

        clock = text("", 13, TEXT_PRIMARY, MEDIUM);
        clock.setLetterSpacing(0.02f);
        profile.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView avatar = text("A", 11, Color.WHITE, MEDIUM);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0xFF3A3A3C);
        avatarBg.setStroke(dp(1), 0x66FFFFFF);
        avatar.setBackground(avatarBg);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        avatarParams.setMargins(dp(10), 0, 0, 0);
        profile.addView(avatar, avatarParams);

        FrameLayout.LayoutParams profileParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34), Gravity.TOP | Gravity.RIGHT);
        profileParams.setMargins(0, dp(22), dp(36), 0);
        scene.addView(profile, profileParams);

        updateClock();
        return scene;
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        String[] configured = getResources().getStringArray(R.array.dock_apps);
        Set<String> dockPackages = new HashSet<>();
        Collections.addAll(dockPackages, configured);
        for (String packageName : configured) {
            AppEntry entry = appForPackage(pm, packageName);
            if (entry != null) dockApps.add(entry);
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        Set<String> added = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (!dockPackages.contains(packageName)
                    && !packageName.equals(getPackageName())
                    && added.add(packageName)) {
                allApps.add(new AppEntry(packageName,
                        info.loadLabel(pm).toString(), info.loadIcon(pm)));
            }
        }
        allApps.sort(Comparator.comparing(entry -> entry.label.toLowerCase(Locale.getDefault())));
    }

    private AppEntry appForPackage(PackageManager pm, String packageName) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
        if (launch == null) return null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            String label = pm.getApplicationLabel(info).toString();
            if ("com.tcl.tv".equals(packageName)) label = "Live TV";
            return new AppEntry(packageName, label, pm.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private void restoreFocus() {
        String packageName = preferences.getString("last-package", null);
        if (packageName != null) {
            int dockIndex = indexOf(dockApps, packageName);
            if (dockIndex >= 0) {
                focusTile(dock, dockIndex);
                return;
            }
            int gridIndex = indexOf(allApps, packageName);
            if (gridIndex >= 0) {
                focusTile(appsRow, gridIndex);
                return;
            }
        }
        focusTile(dock, 0);
    }

    private int indexOf(List<AppEntry> apps, String packageName) {
        for (int index = 0; index < apps.size(); index++) {
            if (packageName.equals(apps.get(index).packageName)) return index;
        }
        return -1;
    }

    private void focusTile(RecyclerView list, int position) {
        if (list == null || position < 0 || position >= list.getAdapter().getItemCount()) return;
        final int request = ++focusRequest;
        list.scrollToPosition(position);
        requestFocusWhenReady(list, position, request, 0);
    }

    private void requestFocusWhenReady(RecyclerView list, int position, int request, int attempt) {
        list.postDelayed(() -> {
            if (request != focusRequest) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
            boolean focused = holder != null && holder.itemView.requestFocus();
            if (focused) return;
            if (attempt < 8) requestFocusWhenReady(list, position, request, attempt + 1);
        }, attempt == 0 ? 40 : 120);
    }

    private void focusDock(int column) {
        if (dockApps.isEmpty()) return;
        focusTile(dock, Math.min(column, dockApps.size() - 1));
    }

    private void focusRec(int position) {
        if (recommendations.isEmpty()) return;
        focusTile(recRow, Math.min(position, recommendations.size() - 1));
    }

    private void focusAppsRow(int position) {
        if (allApps.isEmpty()) return;
        focusTile(appsRow, Math.min(position, allApps.size() - 1));
    }

    private void showInputs() {
        Intent picker = new Intent();
        picker.setComponent(new ComponentName("com.tcl.tv",
                "com.tcl.sourcemenu.sourcemanager.EnableViewInputLauncher"));
        if (picker.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(picker);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall through to the generic picker on other firmware.
            }
        }
        Intent generic = new Intent("com.android.tv.action.VIEW_INPUTS");
        if (generic.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(generic);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall through to the TV app on firmware with a guarded picker.
            }
        }
        openLiveTv();
    }

    private void openLiveTv() {
        AppEntry liveTv = appForPackage(getPackageManager(), "com.tcl.tv");
        if (liveTv != null) launch(liveTv);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException noSettings) {
                toast("Settings is unavailable");
            }
        }
    }

    private void launch(AppEntry entry) {
        try {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(entry.packageName);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (launch == null) {
                toast("Can't open " + entry.label);
                return;
            }
            startActivity(launch);
        } catch (ActivityNotFoundException ignored) {
            toast("Can't open " + entry.label);
        }
    }

    private void feature(AppEntry entry) {
        if (entry == null || (featured != null && featured.packageName.equals(entry.packageName))) {
            return;
        }
        featured = entry;
        preferences.edit().putString("last-package", entry.packageName).apply();
        final int accent = entry.accent();
        boolean barApp = indexOf(dockApps, entry.packageName) >= 0;

        if (!barApp) {
            backdrop.showWallpaper(WALLPAPER_ACCENT);
            hideMetadata();
            return;
        }

        backdrop.showFallback(entry.icon, sceneFor(entry), accent);
        backdrop.setParallax(focusOffset);
        metadata.animate()
                .translationX(-focusOffset * dp(14))
                .setDuration(animationMs)
                .start();

        String provider = ArtworkRepository.providerLabel(entry.packageName);
        String badgeText = provider == null ? entry.label.toUpperCase(Locale.getDefault())
                : provider.toUpperCase(Locale.getDefault());
        boolean loading = repository.supports(entry.packageName) && repository.hasApiKey();
        updateMetadata(badgeText, entry.label,
                loading ? "Loading featured title…" : "Press OK to open", "", false);

        focusedApp = entry;
        final int generation = ++contentGeneration;
        ++focusGeneration;

        repository.load(entry.packageName, generation, (resultGeneration, content) -> {
            if (resultGeneration != contentGeneration) return;
            if (content == null) {
                updateMetadata(badgeText, entry.label, "Press OK to open", "", false);
                return;
            }
            backdrop.showArtwork(content.bitmap,
                    content.ambient != 0 ? content.ambient : accent);
            updateMetadata(content.providerLabel.toUpperCase(Locale.getDefault()),
                    content.title, content.metaLine, content.overview, true);
        });

        prefetchNeighbours(entry.packageName);
    }

    private void hideMetadata() {
        metadata.animate().alpha(0f).setDuration(animationMs).start();
    }

    private void loadRecommendations(AppEntry entry, int generation) {
        recommendations.clear();
        recAdapter.notifyDataSetChanged();
        recSection.animate().cancel();
        recSection.setVisibility(View.GONE);

        repository.loadRecommendations(entry.packageName, generation, (resultGeneration, items) -> {
            if (resultGeneration != contentGeneration) return;
            if (items == null || items.isEmpty()) return;
            recommendations.clear();
            recommendations.addAll(items);
            recAdapter.notifyDataSetChanged();
            recLabel.setText("Recommended · " + items.get(0).providerLabel);
            recSection.setAlpha(0f);
            recSection.setTranslationY(dp(10));
            recSection.setVisibility(View.VISIBLE);
            recSection.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(animationMs == 0 ? 0 : 320)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            for (int i = 0; i < Math.min(8, items.size()); i++) {
                repository.prefetchImage(items.get(i).posterUrl);
            }
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                repository.prefetchImage(items.get(i).backdropUrl);
            }
        });
    }

    private void focusRecommendation(Recommendation recommendation) {
        final int generation = ++focusGeneration;
        updateMetadata(recommendation.providerLabel.toUpperCase(Locale.getDefault()),
                recommendation.title, recommendation.metaLine, recommendation.overview, true,
                "Recommendations from TMDB · general discovery");
        repository.loadImage(recommendation.backdropUrl, bitmap -> {
            if (generation != focusGeneration || bitmap == null) return;
            backdrop.showArtwork(bitmap, ArtworkRepository.ambientFor(bitmap));
        });
    }

    private void prefetchNeighbours(String packageName) {
        ArrayList<AppEntry> combined = new ArrayList<>(allApps);
        combined.addAll(dockApps);
        int index = indexOf(combined, packageName);
        if (index < 0) return;
        for (int offset = -1; offset <= 2; offset++) {
            if (offset == 0) continue;
            int target = index + offset;
            if (target >= 0 && target < combined.size()) {
                repository.prefetch(combined.get(target).packageName);
            }
        }
    }

    private void updateMetadata(String badgeText, String titleText, String metaText,
                                String overviewText, boolean showAttribution) {
        updateMetadata(badgeText, titleText, metaText, overviewText, showAttribution,
                "Artwork & data: TMDB");
    }

    private void updateMetadata(String badgeText, String titleText, String metaText,
                                String overviewText, boolean showAttribution,
                                String attributionText) {
        boolean titleChanged = !title.getText().toString().equals(titleText);
        badge.setText(badgeText);
        title.setText(titleText);
        meta.setText(metaText);
        overview.setText(overviewText);
        overview.setVisibility(TextUtils.isEmpty(overviewText) ? View.INVISIBLE : View.VISIBLE);
        attribution.setText(attributionText);
        attribution.setVisibility(showAttribution ? View.VISIBLE : View.GONE);
        if (metadata.getAlpha() < 1f) {
            metadata.animate()
                    .alpha(1f)
                    .setDuration(animationMs == 0 ? 0 : 240)
                    .start();
        }
        if (titleChanged) {
            metadata.animate().cancel();
            metadata.setAlpha(0.35f);
            metadata.setTranslationY(dp(10));
            metadata.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(animationMs == 0 ? 0 : 300)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private View iconPill(String description, int iconRes, View.OnClickListener listener) {
        FrameLayout pill = new FrameLayout(this);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        pill.setContentDescription(description);
        pill.setBackground(rounded(PILL_IDLE, 17, 1, PILL_BORDER));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(9);
        icon.setPadding(pad, pad, pad, pad);
        icon.setImageTintList(ColorStateList.valueOf(TEXT_SECONDARY));
        pill.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pill.setOnClickListener(listener);
        pill.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(rounded(focused ? Color.WHITE : PILL_IDLE, 17,
                    focused ? 0 : 1, PILL_BORDER));
            icon.setImageTintList(ColorStateList.valueOf(focused ? Color.BLACK : TEXT_SECONDARY));
            view.animate()
                    .scaleX(focused ? 1.14f : 1f)
                    .scaleY(focused ? 1.14f : 1f)
                    .setDuration(animationMs)
                    .start();
        });
        return pill;
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        clock.postDelayed(this::updateClock, 30000);
    }

    private Drawable glass(int fill, int radiusDp, int strokeColor) {
        GradientDrawable base = rounded(fill, radiusDp, 1, strokeColor);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFF3DE, 0x0AFFDDB8, 0x00000000});
        sheen.setCornerRadius(dp(radiusDp));
        return new LayerDrawable(new Drawable[]{base, sheen});
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeWidthDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static int blend(int from, int to, float fraction) {
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * fraction),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final Typeface NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int dominantColor(Drawable drawable) {
        int fallback = 0xFF3B3B4E;
        if (drawable == null) return fallback;
        try {
            Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, 24, 24);
            drawable.draw(canvas);
            long[] weight = new long[64];
            int[] rSum = new int[64];
            int[] gSum = new int[64];
            int[] bSum = new int[64];
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Color.alpha(pixel) < 200) continue;
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    Color.RGBToHSV(r, g, b, hsv);
                    if (hsv[2] < 0.12f) continue;
                    int bucket = (r >> 6) * 16 + (g >> 6) * 4 + (b >> 6);
                    int w = 1 + (int) (hsv[1] * 6);
                    weight[bucket] += w;
                    rSum[bucket] += r * w;
                    gSum[bucket] += g * w;
                    bSum[bucket] += b * w;
                }
            }
            bitmap.recycle();
            int best = -1;
            long bestScore = 0;
            for (int i = 0; i < 64; i++) {
                if (weight[i] > bestScore) {
                    bestScore = weight[i];
                    best = i;
                }
            }
            if (best < 0 || weight[best] == 0) return fallback;
            float[] pick = new float[3];
            Color.RGBToHSV((int) (rSum[best] / weight[best]),
                    (int) (gSum[best] / weight[best]),
                    (int) (bSum[best] / weight[best]), pick);
            pick[1] = Math.min(1f, Math.max(0.42f, pick[1] * 1.15f));
            pick[2] = 0.66f;
            return Color.HSVToColor(pick);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private final class RecommendationsAdapter extends RecyclerView.Adapter<RecHolder> {
        @Override
        public RecHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecHolder(new RecCard(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecHolder holder, int position) {
            holder.bind(recommendations.get(position));
        }

        @Override
        public int getItemCount() {
            return recommendations.size();
        }
    }

    private final class RecHolder extends RecyclerView.ViewHolder {
        private final RecCard card;

        RecHolder(RecCard card) {
            super(card);
            this.card = card;
        }

        void bind(Recommendation recommendation) {
            card.label.setText(recommendation.title);
            card.setContentDescription(recommendation.title);
            card.image.setTag(recommendation.posterUrl);
            card.image.setImageDrawable(null);
            card.image.setAlpha(0f);
            int accent = focusedApp != null ? focusedApp.accent() : 0xFF8A6A46;
            card.imageFrame.setBackground(rounded(withAlpha(accent, 0x55), 11, 0, 0));

            repository.loadImage(recommendation.posterUrl, bitmap -> {
                if (bitmap == null) return;
                if (!recommendation.posterUrl.equals(card.image.getTag())) return;
                card.image.setImageBitmap(bitmap);
                card.image.animate().alpha(1f).setDuration(220).start();
            });

            card.setOnClickListener(view -> {
                if (focusedApp != null) launch(focusedApp);
            });
            card.setOnFocusChangeListener((view, focused) -> {
                view.animate()
                        .scaleX(focused ? 1.06f : 1f)
                        .scaleY(focused ? 1.06f : 1f)
                        .translationY(focused ? -dp(3) : 0f)
                        .setDuration(animationMs)
                        .setInterpolator(focusInterpolator)
                        .start();
                view.setBackground(focused ? card.focusBackground : card.idleBackground);
                view.setElevation(focused ? dp(14) : dp(3));
                card.label.setTextColor(focused ? Color.WHITE : TEXT_SECONDARY);
                if (focused) focusRecommendation(recommendation);
            });
            card.setOnKeyListener((view, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    focusAppsRow(0);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusDock(0);
                    return true;
                }
                return false;
            });
        }
    }

    private final class RecCard extends FrameLayout {
        final FrameLayout imageFrame;
        final ImageView image;
        final TextView label;
        final Drawable idleBackground;
        final Drawable focusBackground;

        RecCard(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            setCameraDistance(dp(1600));
            idleBackground = rounded(0x14FFF3E3, 14, 1, 0x26FFE8CC);
            focusBackground = rounded(0x2BFFF3E3, 14, 2, 0xCCFFF4E2);
            setBackground(idleBackground);
            setElevation(dp(3));

            imageFrame = new FrameLayout(context);
            imageFrame.setClipToOutline(true);
            imageFrame.setBackground(rounded(ICON_SURFACE, 11, 0, 0));

            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setAlpha(0f);
            imageFrame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View gloss = new View(context);
            GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x1FFFF6E9, 0x00FFFFFF});
            sheen.setCornerRadius(dp(11));
            gloss.setBackground(sheen);
            gloss.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            imageFrame.addView(gloss, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setClipChildren(false);
            content.addView(imageFrame, new LinearLayout.LayoutParams(dp(126), dp(71)));

            label = text("", 9, TEXT_SECONDARY, MEDIUM);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setShadowLayer(8f, 0f, 2f, 0xCC000000);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
            labelParams.topMargin = dp(3);
            content.addView(label, labelParams);

            addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(136), dp(92));
            params.setMargins(dp(4), dp(2), dp(4), dp(2));
            setLayoutParams(params);
        }
    }

    private final class AppsAdapter extends RecyclerView.Adapter<AppHolder> {
        private final List<AppEntry> apps;
        private final boolean isDock;

        AppsAdapter(List<AppEntry> apps, boolean isDock) {
            this.apps = apps;
            this.isDock = isDock;
        }

        @Override
        public AppHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new AppHolder(new AppTile(parent.getContext(), isDock));
        }

        @Override
        public void onBindViewHolder(AppHolder holder, int position) {
            holder.bind(apps.get(position), position);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        private final AppTile tile;

        AppHolder(AppTile tile) {
            super(tile);
            this.tile = tile;
        }

        void bind(AppEntry entry, int position) {
            tile.bind(entry);
            tile.setOnClickListener(v -> launch(entry));

            tile.setOnFocusChangeListener((view, focused) -> {
                tile.setFocused(focused);
                if (focused) feature(entry);
            });

            tile.setOnKeyListener((view, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int count = tile.isDock ? dockApps.size() : allApps.size();
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) return true;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && position == count - 1) return true;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (tile.isDock) return true;
                    focusDock(Math.min(position, dockApps.size() - 1));
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!tile.isDock) return true;
                    focusAppsRow(position);
                    return true;
                }
                return false;
            });
        }
    }

    private final class AppTile extends FrameLayout {
        final ImageView art;
        final FrameLayout artFrame;
        final View halo;
        final boolean isDock;
        int accent = 0xFF3B3B4E;

        AppTile(Context context, boolean isDock) {
            super(context);
            this.isDock = isDock;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            setPadding(0, 0, 0, 0);

            halo = new HaloView(context);
            addView(halo, new FrameLayout.LayoutParams(
                    dp(TILE_W + 18), dp(TILE_H + 18), Gravity.CENTER));

            artFrame = new FrameLayout(context);
            artFrame.setClipToOutline(true);
            artFrame.setBackground(rounded(0xFF1C1C1E, TILE_RADIUS, 1, 0x24FFFFFF));

            art = new ImageView(context);
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            artFrame.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View sheen = new View(context);
            GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x1AFFFFFF, 0x00FFFFFF});
            gloss.setCornerRadius(dp(TILE_RADIUS));
            sheen.setBackground(gloss);
            sheen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            artFrame.addView(sheen, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            addView(artFrame, new FrameLayout.LayoutParams(
                    dp(TILE_W), dp(TILE_H), Gravity.CENTER));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    dp(TILE_W), dp(TILE_H));
            params.setMargins(dp(TILE_GAP / 2), 0, dp(TILE_GAP / 2), 0);
            setLayoutParams(params);
        }

        void bind(AppEntry entry) {
            setContentDescription(entry.label);
            accent = entry.accent();
            Drawable banner = entry.banner(getApplicationContext());
            if (banner != null) {
                art.setPadding(0, 0, 0, 0);
                art.setImageDrawable(banner);
                artFrame.setBackground(rounded(entry.bannerBackground(), TILE_RADIUS, 0, 0));
            } else {
                art.setPadding(dp(14), dp(14), dp(14), dp(14));
                art.setImageDrawable(entry.icon);
                artFrame.setBackground(rounded(accent, TILE_RADIUS, 0, 0));
            }
        }

        void setFocused(boolean focused) {
            animate()
                    .scaleX(focused ? 1.05f : 1f)
                    .scaleY(focused ? 1.05f : 1f)
                    .translationY(focused ? -dp(2) : 0f)
                    .setDuration(animationMs)
                    .setInterpolator(focusInterpolator)
                    .start();
            halo.animate().alpha(focused ? 1f : 0f).setDuration(animationMs).start();
            artFrame.setElevation(focused ? dp(10) : 0);
        }
    }

    /** Soft white halo drawn outside the tile, matching the tvOS focus treatment. */
    private final class HaloView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        HaloView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setAlpha(0f);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(0xE6FFFFFF);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(dp(6));
            glow.setColor(0x2EFFFFFF);
            glow.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dp(5);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float radius = dp(TILE_RADIUS + 4);
            canvas.drawRoundRect(rect, radius, radius, glow);
            canvas.drawRoundRect(rect, radius, radius, ring);
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        Bitmap scene;
        int accent;
        private Drawable banner;
        private boolean bannerResolved;
        private int bannerBg;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }

        int accent() {
            if (accent == 0) accent = dominantColor(icon);
            return accent;
        }

        /** tvOS-style landscape art: the app's own Android TV banner, when it ships one. */
        Drawable banner(Context context) {
            if (!bannerResolved) {
                bannerResolved = true;
                banner = loadBanner(context, packageName);
            }
            return banner;
        }

        int bannerBackground() {
            if (bannerBg == 0) {
                bannerBg = banner == null ? accent() : edgeColor(banner);
            }
            return bannerBg;
        }
    }

    private static Drawable loadBanner(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            int resId = 0;
            Intent leanback = pm.getLeanbackLaunchIntentForPackage(packageName);
            ResolveInfo resolved = leanback == null ? null : pm.resolveActivity(leanback, 0);
            if (resolved != null && resolved.activityInfo != null) {
                resId = resolved.activityInfo.banner;
            }
            ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
            if (resId == 0) resId = app.banner;
            if (resId == 0) return null;
            return pm.getResourcesForApplication(app).getDrawable(resId, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Samples the banner's corners so the letterbox around 16:9 art blends in. */
    private static int edgeColor(Drawable drawable) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, 32, 18);
            drawable.draw(canvas);
            int[][] points = {{0, 0}, {31, 0}, {0, 17}, {31, 17}};
            long r = 0;
            long g = 0;
            long b = 0;
            int count = 0;
            for (int[] point : points) {
                int pixel = bitmap.getPixel(point[0], point[1]);
                if (Color.alpha(pixel) < 200) continue;
                r += Color.red(pixel);
                g += Color.green(pixel);
                b += Color.blue(pixel);
                count++;
            }
            bitmap.recycle();
            if (count == 0) return 0xFF1C1C1E;
            int rr = (int) (r / count);
            int gg = (int) (g / count);
            int bb = (int) (b / count);
            return Color.rgb((rr * 94 + 255 * 6) / 100,
                    (gg * 94 + 255 * 6) / 100,
                    (bb * 94 + 255 * 6) / 100);
        } catch (Exception ignored) {
            return 0xFF1C1C1E;
        }
    }
}
