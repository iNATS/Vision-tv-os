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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
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

    private final ArrayList<AppEntry> dockApps = new ArrayList<>();
    private final ArrayList<AppEntry> allApps = new ArrayList<>();

    private RecyclerView dock;
    private RecyclerView appsRow;
    private View inputsButton;
    private TextView clock;
    private TextView date;
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
            focusAppsRow(0);
        } else {
            focusDock(0);
        }
    }

    private View buildScene() {
        FrameLayout scene = new FrameLayout(this);
        scene.setBackgroundColor(Color.BLACK);
        backdrop = new CinematicBackdrop(this);
        scene.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(16), dp(40), dp(12));
        scene.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordmark = text("Vision TV", 13, TEXT_PRIMARY, MEDIUM);
        wordmark.setLetterSpacing(0.02f);
        date = text("", 9, TEXT_TERTIARY, NORMAL);
        date.setLetterSpacing(0.12f);
        date.setSingleLine(true);
        identity.addView(wordmark);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(2);
        identity.addView(date, dateParams);
        header.addView(identity, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setClipChildren(false);
        inputsButton = iconPill("Inputs", R.drawable.ic_inputs, v -> showInputs());
        View wifi = iconPill("Wi-Fi", R.drawable.ic_wifi,
                v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        View settings = iconPill("Settings", R.drawable.ic_settings,
                v -> openSettings(Settings.ACTION_SETTINGS));
        View[] topButtons = {inputsButton, wifi, settings};
        for (View button : topButtons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.setMargins(dp(8), 0, 0, 0);
            actions.addView(button, params);
            button.setOnKeyListener((view, code, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusAppsRow(0);
                    return true;
                }
                return false;
            });
        }
        header.addView(actions);

        clock = text("", 12, TEXT_SECONDARY, MEDIUM);
        clock.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockParams.setMargins(dp(18), 0, 0, 0);
        header.addView(clock, clockParams);

        appsRow = new RecyclerView(this);
        appsRow.setClipToPadding(false);
        appsRow.setClipChildren(false);
        appsRow.setPadding(dp(2), dp(6), dp(2), dp(2));
        appsRow.setLayoutManager(new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false));
        appsRow.setAdapter(new AppsAdapter(allApps, false));
        appsRow.setItemAnimator(null);
        appsRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appsRow.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        rowParams.topMargin = dp(8);
        root.addView(appsRow, rowParams);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        metadata.setClipChildren(false);
        root.addView(metadata, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        badge = text("", 9, TEXT_CHAMPAGNE, MEDIUM);
        badge.setLetterSpacing(0.16f);
        badge.setSingleLine(true);
        badge.setText("VISION TV");
        metadata.addView(badge);

        title = text("", 27, TEXT_PRIMARY, MEDIUM);
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
        overview.setMaxWidth(dp(620));
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

        recSection = new LinearLayout(this);
        recSection.setOrientation(LinearLayout.VERTICAL);
        recSection.setClipChildren(false);
        recSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams recSectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recSectionParams.topMargin = dp(9);
        root.addView(recSection, recSectionParams);

        recLabel = text("", 9, TEXT_CHAMPAGNE, MEDIUM);
        recLabel.setLetterSpacing(0.16f);
        recLabel.setSingleLine(true);
        recSection.addView(recLabel);

        recRow = new RecyclerView(this);
        recRow.setClipToPadding(false);
        recRow.setClipChildren(false);
        recRow.setPadding(dp(2), dp(4), dp(2), dp(4));
        recRow.setLayoutManager(new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false));
        recAdapter = new RecommendationsAdapter();
        recRow.setAdapter(recAdapter);
        recRow.setItemAnimator(null);
        recRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recRow.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams recRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        recRowParams.topMargin = dp(3);
        recSection.addView(recRow, recRowParams);

        LinearLayout dockWrap = new LinearLayout(this);
        dockWrap.setGravity(Gravity.CENTER);
        dockWrap.setOrientation(LinearLayout.HORIZONTAL);
        dockWrap.setClipChildren(false);

        LinearLayout dockBand = new LinearLayout(this);
        dockBand.setGravity(Gravity.CENTER);
        dockBand.setClipChildren(false);
        dockBand.setClipToPadding(false);
        dockBand.setPadding(dp(12), 0, dp(12), 0);
        dockBand.setBackground(glass(DOCK_SURFACE, 24, DOCK_BORDER));
        dockBand.setElevation(dp(14));
        dockBand.setOutlineSpotShadowColor(0x2EFFFFFF);
        dockBand.setOutlineAmbientShadowColor(0x14000000);

        dock = new RecyclerView(this);
        dock.setClipToPadding(false);
        dock.setClipChildren(false);
        dock.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dock.setVerticalScrollBarEnabled(false);
        dock.setHorizontalScrollBarEnabled(false);
        dock.setLayoutManager(new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false));
        dock.setAdapter(new AppsAdapter(dockApps, true));
        dock.setItemAnimator(null);
        dockBand.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(80)));
        dockWrap.addView(dockBand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(86)));
        LinearLayout.LayoutParams dockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(86));
        dockParams.topMargin = dp(8);
        root.addView(dockWrap, dockParams);

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

        loadRecommendations(entry, generation);
        prefetchNeighbours(entry.packageName);
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
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
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
            tile.icon.setImageDrawable(entry.icon);
            tile.label.setText(entry.label);
            tile.setContentDescription(entry.label);
            tile.accent = entry.accent();
            tile.setPlated(entry.icon == null || entry.icon.getOpacity() != PixelFormat.OPAQUE);
            tile.iconFrame.setBackground(tile.hasFocus()
                    ? tile.focusIconBackground : tile.idleIconBackground);
            tile.bloom.setBackground(tile.bloomDrawable());
            tile.setOnClickListener(v -> launch(entry));

            tile.setOnFocusChangeListener((view, focused) -> {
                float scale = tile.isDock ? 1.09f : 1.12f;
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float centerX = location[0] + view.getWidth() / 2f;
                float screenCenter = getResources().getDisplayMetrics().widthPixels / 2f;
                float tilt = 0f;
                if (focused) {
                    tilt = Math.max(-7f, Math.min(7f,
                            (centerX - screenCenter) / screenCenter * 10f));
                }
                tile.animate()
                        .scaleX(focused ? scale : 1f)
                        .scaleY(focused ? scale : 1f)
                        .rotationY(tilt)
                        .translationY(focused ? -dp(tile.isDock ? 3 : 6) : 0f)
                        .setDuration(animationMs)
                        .setInterpolator(focusInterpolator)
                        .start();
                tile.icon.setTranslationX(focused ? -tilt * dp(1) * 0.7f : 0f);
                tile.bloom.animate()
                        .alpha(focused ? 1f : 0f)
                        .setDuration(animationMs)
                        .start();
                tile.iconFrame.setBackground(focused
                        ? tile.focusIconBackground : tile.idleIconBackground);
                tile.iconFrame.setElevation(focused ? dp(18) : dp(2));
                tile.iconFrame.setOutlineSpotShadowColor(focused
                        ? withAlpha(tile.accent, 0xCC) : 0x00000000);
                tile.iconFrame.setOutlineAmbientShadowColor(focused
                        ? withAlpha(tile.accent, 0x55) : 0x00000000);
                tile.label.setTextColor(focused ? Color.WHITE : TEXT_SECONDARY);
                if (focused) feature(entry);
            });

            tile.setOnKeyListener((view, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int count = tile.isDock ? dockApps.size() : allApps.size();
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) return true;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && position == count - 1) return true;
                if (tile.isDock) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        if (recSection != null && recSection.getVisibility() == View.VISIBLE) {
                            focusRec(0);
                        } else {
                            focusAppsRow(position);
                        }
                        return true;
                    }
                    return false;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    inputsButton.requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (recSection != null && recSection.getVisibility() == View.VISIBLE) {
                        focusRec(0);
                    } else {
                        focusDock(position);
                    }
                    return true;
                }
                return false;
            });
        }
    }

    private final class AppTile extends FrameLayout {
        final ImageView icon;
        final TextView label;
        final FrameLayout iconFrame;
        final View bloom;
        final boolean isDock;
        Drawable focusIconBackground;
        Drawable idleIconBackground;
        int radius;
        int accent = 0xFF3B3B4E;

        AppTile(Context context, boolean isDock) {
            super(context);
            this.isDock = isDock;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            setPadding(0, 0, 0, 0);
            setCameraDistance(dp(1800));

            int frameSize = isDock ? dp(50) : dp(84);
            radius = isDock ? 12 : 20;

            focusIconBackground = rounded(ICON_SURFACE, radius, 2, FOCUS_RING);
            idleIconBackground = rounded(ICON_SURFACE, radius, 1, ICON_BORDER);

            bloom = new View(context);
            bloom.setAlpha(0f);
            bloom.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bloomParams = new FrameLayout.LayoutParams(
                    frameSize + dp(54), frameSize + dp(54), Gravity.CENTER);
            addView(bloom, bloomParams);

            iconFrame = new FrameLayout(context);
            iconFrame.setClipToOutline(true);
            iconFrame.setBackground(idleIconBackground);
            iconFrame.setElevation(dp(2));
            iconFrame.setOutlineSpotShadowColor(0x00000000);
            iconFrame.setOutlineAmbientShadowColor(0x00000000);

            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconPadding = isDock ? dp(3) : dp(6);
            icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            iconFrame.addView(icon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View sheen = new View(context);
            GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x1FFFF6E9, 0x08FFE7C6, 0x00000000});
            gloss.setCornerRadius(dp(radius));
            sheen.setBackground(gloss);
            sheen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconFrame.addView(sheen, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            content.addView(iconFrame, new LinearLayout.LayoutParams(frameSize, frameSize));

            label = text("", isDock ? 9 : 10, TEXT_SECONDARY, MEDIUM);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setLetterSpacing(0.01f);
            label.setShadowLayer(8f, 0f, 2f, 0xCC000000);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
            labelParams.topMargin = isDock ? dp(3) : dp(6);
            content.addView(label, labelParams);

            addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            int tileWidth = isDock ? dp(68) : dp(96);
            int tileHeight = isDock ? dp(78) : dp(122);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(tileWidth, tileHeight);
            params.setMargins(dp(4), dp(isDock ? 2 : 4), dp(4), dp(isDock ? 2 : 4));
            setLayoutParams(params);
        }

        void setPlated(boolean plated) {
            focusIconBackground = plated
                    ? rounded(ICON_SURFACE, radius, 2, FOCUS_RING)
                    : rounded(Color.TRANSPARENT, radius, 2, 0xCCFFFFFF);
            idleIconBackground = plated
                    ? rounded(ICON_SURFACE, radius, 1, ICON_BORDER)
                    : rounded(Color.TRANSPARENT, radius, 0, Color.TRANSPARENT);
            iconFrame.setBackground(hasFocus() ? focusIconBackground : idleIconBackground);
        }

        Drawable bloomDrawable() {
            GradientDrawable glow = new GradientDrawable();
            glow.setShape(GradientDrawable.OVAL);
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientRadius(dp(isDock ? 58 : 96));
            glow.setGradientCenter(0.5f, 0.5f);
            int warm = blend(accent, WARM_AMBER, 0.18f);
            glow.setColors(new int[]{withAlpha(warm, 0x70), withAlpha(warm, 0x26), 0x00000000});
            return glow;
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        Bitmap scene;
        int accent;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }

        int accent() {
            if (accent == 0) accent = dominantColor(icon);
            return accent;
        }
    }
}
