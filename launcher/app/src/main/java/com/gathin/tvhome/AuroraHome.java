package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gleb's fork: the Aurora home screen, shown by MainActivity instead of the grid when aurora_home is on.
 * Clock and date, a Home / Apps / Settings tab bar whose pill follows focus, PS5 / backlight / VPN buttons in the
 * header, the apps on Home and Nuvio's Continue watching. MainActivity keeps the window, the Home key path and the
 * key helpers.
 */
final class AuroraHome {

    private static final int TXT = 0xFFF3F6FB, SUB = 0xFFC3CBD9, TAB_IDLE = 0xFFD9E0EC, INK = 0xFF0B1020,
            TILE = 0xFF141A28;
    private static final int GREEN = 0xFF34D27A, RED = 0xFFFF5A4F, AMBER = 0xFFFFC478, PS_BLUE = 0xFF00439C;
    private static final int PILL_IDLE = 0x29FFFFFF, PILL_ACTIVE = 0xFFFFFFFF;
    private static final long PILL_MS = 260, PAGE_MS = 240, UNFOLD_MS = 300, LABEL_DELAY_MS = 110;
    private static final long LIGHT_POLL_MS = 20_000;
    private static final int HOME = 0, APPS = 1, SETTINGS = 2;
    private static final int GRID_COLUMNS = 6, WATCH_PORTRAIT = 7, WATCH_PORTRAIT_ABOVE = 9, WATCH_LANDSCAPE = 4;
    /** Header button height in dp; the round ones are this wide when folded. */
    private static final int BUTTON_DP = 36;
    /** Banners are drawn once into a bitmap this size (16:9), off the main thread, and reused by every tile. */
    private static final int BANNER_W = 400, BANNER_H = 225;
    private static final String HAPP = "com.happproxy", NUVIO = "com.nuvio.tv";
    private static final String PS5_INPUT = "com.tcl.tvinput/.TvPassThroughService/HW15";

    private static final ExecutorService BANNER_IO = Executors.newSingleThreadExecutor();
    /** pkg to its banner bitmap, or to NO_BANNER for an app without one. */
    private static final LruCache<String, Object> BANNERS = new LruCache<String, Object>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Object value) {
            return value instanceof Bitmap ? ((Bitmap) value).getByteCount() : 1;
        }
    };
    private static final Object NO_BANNER = new Object();

    private final Activity activity;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Config cfg;
    private JSONObject fork;
    private WallpaperPlayer wallpaper;
    private AuroraSky sky;
    private FrameLayout root;
    private View pill;
    private GradientDrawable pillShape;
    private ViewGroup tabRow;
    private final TextView[] tabs = new TextView[3];
    private final View[] pages = new View[3];
    private LinearLayout appRow;
    private final List<View> appTiles = new ArrayList<>();
    private int selected = HOME;
    private ValueAnimator pillWidth;
    private boolean appsBuilt;
    private ButtonParts vpn, light;
    private Govee govee;
    private Boolean lightOn;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private TextView watchTitle;
    private LinearLayout watchRow;
    private View settingsScroll, picker;
    private TextView homeAppsValue;
    private View menu;
    private View menuAnchor;
    private int packageSequence;
    private boolean resumed;

    /** The parts of a header button that change with state. */
    private static final class ButtonParts {
        RingFrame frame;
        GradientDrawable disc;
        ImageView icon;
        TextView title, sub;
    }

    AuroraHome(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /** Whether to animate: off when the TV's animator scale is 0 (Developer options, or Remove animations). */
    static boolean motion(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    void create() {
        activity.setContentView(R.layout.aurora_home);
        cfg = Config.load(activity);
        fork = Config.overlay(activity);
        if (fork == null) fork = new JSONObject();
        root = activity.findViewById(R.id.aurora_root);
        activity.getWindow().setBackgroundDrawable(null);
        // The sky is drawn, not decoded, so it has no JPEG blocks or 16-bit bands; a theme picture still wins.
        sky = new AuroraSky(activity, AuroraPrefs.text(activity, "background", AuroraSky.SKIES[0].key));
        root.addView(sky, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallpaper = new WallpaperPlayer(activity, root, cfg);
        govee = Govee.from(activity);
        ChangedPackages changes = activity.getPackageManager().getChangedPackages(0);
        packageSequence = changes != null ? changes.getSequenceNumber() : 0;
        buildTabs();
        buildSide(activity.findViewById(R.id.aurora_side));
        appRow = activity.findViewById(R.id.aurora_app_row);
        buildHomeRow();
        settingsScroll = activity.findViewById(R.id.aurora_settings_scroll);
        picker = activity.findViewById(R.id.aurora_picker);
        buildSettings(activity.findViewById(R.id.aurora_settings_main));
        watchTitle = activity.findViewById(R.id.aurora_watch_title);
        watchRow = activity.findViewById(R.id.aurora_watch_row);
        // Blocks for the Glide focus style: the ring slides inside each, and fades between them. Settings has
        // none: a ring sliding over its wide rows looks heavy.
        FocusGlide.block(appRow);
        FocusGlide.block(watchRow);
        FocusGlide.block(activity.findViewById(R.id.aurora_apps_grid));
        root.post(() -> {
            placePill(false);
            focusFirst(HOME);
        });
    }

    // ---- tabs ------------------------------------------------------------------------------------

    private void buildTabs() {
        pill = activity.findViewById(R.id.aurora_pill);
        pillShape = new GradientDrawable();
        pillShape.setCornerRadius(dp(999));
        pillShape.setColor(PILL_IDLE);
        pill.setBackground(pillShape);
        tabRow = activity.findViewById(R.id.aurora_tab_row);
        tabs[HOME] = activity.findViewById(R.id.aurora_tab_home);
        tabs[APPS] = activity.findViewById(R.id.aurora_tab_apps);
        tabs[SETTINGS] = activity.findViewById(R.id.aurora_tab_settings);
        pages[HOME] = activity.findViewById(R.id.aurora_page_home);
        pages[APPS] = activity.findViewById(R.id.aurora_page_apps);
        pages[SETTINGS] = activity.findViewById(R.id.aurora_page_settings);
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            // The tab under focus is the selected one: Left and Right switch pages without OK.
            tabs[i].setOnFocusChangeListener((v, has) -> {
                if (has) select(index);
                v.post(this::paintTabs);
            });
        }
        paintTabs();
    }

    private void select(int index) {
        if (index == selected) return;
        int previous = selected;
        selected = index;
        if (index == APPS && !appsBuilt) buildAllApps();
        if (previous == SETTINGS && picker.getVisibility() == View.VISIBLE) closePicker(false);
        placePill(true);
        showPage(previous, index);
    }

    /** The pill is white while the bar has focus (it is the focus there), and a faint glass marker otherwise. */
    private void paintTabs() {
        boolean barFocused = tabRow.hasFocus();
        pillShape.setColor(barFocused ? PILL_ACTIVE : PILL_IDLE);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i != selected ? TAB_IDLE : barFocused ? INK : TXT);
        }
    }

    private void placePill(boolean animate) {
        TextView tab = tabs[selected];
        int width = tab.getWidth();
        float x = tab.getLeft();
        if (width == 0) return;
        if (pillWidth != null) pillWidth.cancel();
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        if (!animate || !motion(activity) || lp.width <= 0) {
            lp.width = width;
            pill.setLayoutParams(lp);
            pill.setTranslationX(x);
            paintTabs();
            return;
        }
        pill.animate().translationX(x).setDuration(PILL_MS).setInterpolator(new DecelerateInterpolator(2f)).start();
        pillWidth = ValueAnimator.ofInt(lp.width, width);
        pillWidth.setDuration(PILL_MS);
        pillWidth.setInterpolator(new DecelerateInterpolator(2f));
        pillWidth.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            pill.setLayoutParams(lp);
        });
        pillWidth.start();
        paintTabs();
    }

    /** Pages cross-fade through a hardware layer: fading a whole page of tiles without one redraws it every frame. */
    private void showPage(int from, int to) {
        crossFade(pages[from], pages[to]);
    }

    private void crossFade(View out, View in) {
        out.animate().cancel();
        in.animate().cancel();
        if (!motion(activity)) {
            out.setVisibility(View.INVISIBLE);
            in.setAlpha(1f);
            in.setTranslationY(0f);
            in.setVisibility(View.VISIBLE);
            return;
        }
        out.animate().alpha(0f).setDuration(PAGE_MS / 2).withLayer()
                .withEndAction(() -> {
                    out.setVisibility(View.INVISIBLE);
                    out.setAlpha(1f);
                }).start();
        in.setAlpha(0f);
        in.setTranslationY(dp(10));
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).translationY(0f).setDuration(PAGE_MS).withLayer()
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /**
     * Predictable movement between the bar and the pages: Up from a page's top row always lands on the selected
     * tab, and Down from the bar on the page's first item. While a tile menu is open, the keys stay in it.
     */
    boolean onKey(KeyEvent event) {
        if (menu != null) return menuKey(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        View focused = activity.getCurrentFocus();
        if (focused == null) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_UP && isInside(focused, pages[selected])) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) pages[selected], focused, View.FOCUS_UP);
            if (next == null) {
                tabs[selected].requestFocus();
                return true;
            }
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && isInside(focused, tabRow)) {
            return focusFirst(selected);
        }
        return false;
    }

    private static boolean isInside(View view, View parent) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == parent) return true;
        }
        return false;
    }

    private boolean focusFirst(int page) {
        View first = page == HOME && !appTiles.isEmpty() ? appTiles.get(0) : firstFocusable((ViewGroup) pages[page]);
        if (first != null) return first.requestFocus();
        return tabs[page].requestFocus();
    }

    private static View firstFocusable(ViewGroup group) {
        ArrayList<View> all = new ArrayList<>();
        group.addFocusables(all, View.FOCUS_DOWN);
        return all.isEmpty() ? null : all.get(0);
    }

    // ---- header buttons --------------------------------------------------------------------------

    private void buildSide(LinearLayout side) {
        ButtonParts ps = button(R.drawable.ic_playstation, PS_BLUE, 0xFFFFFFFF, R.string.aurora_ps5,
                R.string.aurora_hdmi1, false, v -> openPs5());
        side.addView(ps.frame);
        if (govee != null) {
            light = button(R.drawable.ic_backlight, 0x14FFFFFF, SUB, R.string.aurora_backlight, R.string.aurora_govee,
                    true, v -> toggleLight());
            side.addView(light.frame);
        }
        vpn = button(R.drawable.ic_lock_closed, 0x2934D27A, GREEN, R.string.aurora_vpn_on, R.string.aurora_happ,
                true, v -> openApp(cfg.vpnApp.isEmpty() ? HAPP : cfg.vpnApp));
        side.addView(vpn.frame);
    }

    /**
     * A header button: an icon disc and two lines of text on the frosted pill. A round one shows only the disc and
     * unfolds into the full pill while focused: the width grows first, then the text fades in.
     */
    private ButtonParts button(int icon, int discColor, int iconColor, int title, int sub, boolean round,
                               View.OnClickListener click) {
        ButtonParts parts = new ButtonParts();
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setBackgroundResource(R.drawable.aurora_glass);
        // Equal padding: folded, the disc fills exactly the round button's inner width.
        body.setPadding(dp(6), 0, dp(6), 0);

        FrameLayout disc = new FrameLayout(activity);
        parts.disc = new GradientDrawable();
        parts.disc.setShape(GradientDrawable.OVAL);
        parts.disc.setColor(discColor);
        disc.setBackground(parts.disc);
        parts.icon = new ImageView(activity);
        parts.icon.setImageResource(icon);
        parts.icon.setColorFilter(iconColor);
        disc.addView(parts.icon, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        int discSize = dp(BUTTON_DP - 12);
        body.addView(disc, new LinearLayout.LayoutParams(discSize, discSize));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        parts.title = new TextView(activity, null, 0, R.style.AuroraButtonTitle);
        parts.title.setText(title);
        parts.sub = new TextView(activity, null, 0, R.style.AuroraButtonSub);
        parts.sub.setText(sub);
        parts.sub.setPadding(0, dp(1), 0, 0);
        text.addView(parts.title);
        text.addView(parts.sub);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart(dp(7));
        textLp.setMarginEnd(dp(8));
        body.addView(text, textLp);

        RingFrame frame = new RingFrame(activity).corners(0, true).lift(1.04f, 0);
        frame.content(body);
        frame.setOnClickListener(click);
        int height = dp(BUTTON_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                height + 2 * dp(RingFrame.GAP_DP + RingFrame.STROKE_DP));
        frame.setLayoutParams(lp);
        FrameLayout.LayoutParams bodyLp = (FrameLayout.LayoutParams) body.getLayoutParams();
        bodyLp.height = height;
        if (round) {
            bodyLp.width = height;
            text.setAlpha(0f);
            frame.setOnFocusChangeListener((v, has) -> {
                unfold(body, text, has);
                if (has && parts == light) refreshLight();
            });
        } else {
            bodyLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        body.setLayoutParams(bodyLp);
        parts.frame = frame;
        return parts;
    }

    private void unfold(LinearLayout body, View text, boolean open) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) body.getLayoutParams();
        int closed = dp(BUTTON_DP);
        body.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(closed, View.MeasureSpec.EXACTLY));
        int target = open ? body.getMeasuredWidth() : closed;
        Object running = body.getTag();
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        text.animate().cancel();
        if (!motion(activity)) {
            lp.width = target;
            body.setLayoutParams(lp);
            text.setAlpha(open ? 1f : 0f);
            return;
        }
        int from = lp.width > 0 ? lp.width : closed;
        ValueAnimator width = ValueAnimator.ofInt(from, target);
        width.setDuration(UNFOLD_MS);
        width.setInterpolator(new DecelerateInterpolator(2f));
        width.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            body.setLayoutParams(lp);
        });
        body.setTag(width);
        width.start();
        // Opening: the text waits for the width to grow. Closing: it goes first.
        text.animate().alpha(open ? 1f : 0f).setStartDelay(open ? LABEL_DELAY_MS : 0)
                .setDuration(open ? 200 : 120).start();
    }

    // ---- PS5 -------------------------------------------------------------------------------------

    /**
     * Switches to HDMI 1. The PS5's own CEC input (a child of the port) is preferred: opening it also makes the PS5
     * the active source, which wakes it. ps5_input in the theme names the port.
     */
    private void openPs5() {
        String port = fork.optString("ps5_input", PS5_INPUT);
        String target = port;
        TvInputManager tim = activity.getSystemService(TvInputManager.class);
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (port.equals(info.getParentId())) {
                        target = info.getId();
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(target);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!cfg.liveTvPackage.isEmpty()) {
            try {
                activity.startActivity(new Intent(view).setPackage(cfg.liveTvPackage));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        start(view);
    }

    // ---- backlight (Govee) -----------------------------------------------------------------------

    private void toggleLight() {
        boolean want = lightOn == null || !lightOn;
        paintLight(want);
        govee.power(want, on -> {
            if (on == null) {
                Toast.makeText(activity, R.string.aurora_backlight_failed, Toast.LENGTH_SHORT).show();
                refreshLight();
            } else {
                paintLight(on);
            }
        });
    }

    private void refreshLight() {
        if (govee != null) govee.state(on -> {
            if (on != null) paintLight(on);
        });
    }

    /** The light can be switched from the Govee app or its own remote too: Home asks again every 20 s while shown. */
    private final Runnable lightPoll = new Runnable() {
        @Override
        public void run() {
            refreshLight();
            if (resumed) main.postDelayed(this, LIGHT_POLL_MS);
        }
    };

    private void paintLight(Boolean on) {
        lightOn = on;
        if (light == null) return;
        boolean lit = on != null && on;
        light.icon.setColorFilter(lit ? AMBER : SUB);
        light.disc.setColor(lit ? 0x29FFC478 : 0x14FFFFFF);
        light.title.setText(on == null ? R.string.aurora_backlight
                : on ? R.string.aurora_backlight_on : R.string.aurora_backlight_off);
    }

    // ---- VPN -------------------------------------------------------------------------------------

    private void refreshVpn() {
        boolean up = vpnUp();
        vpn.icon.setImageResource(up ? R.drawable.ic_lock_closed : R.drawable.ic_lock_open);
        vpn.icon.setColorFilter(up ? GREEN : RED);
        vpn.disc.setColor(up ? 0x2934D27A : 0x29FF5A4F);
        vpn.title.setText(up ? R.string.aurora_vpn_on : R.string.aurora_vpn_off);
        vpn.sub.setText(up ? R.string.aurora_happ : R.string.aurora_open_happ);
    }

    private boolean vpnUp() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    // ---- apps ------------------------------------------------------------------------------------

    private List<String> homeApps() {
        PackageManager pm = activity.getPackageManager();
        List<String> apps = new ArrayList<>();
        for (String pkg : AuroraPrefs.homeApps(activity, cfg.dockApps)) {
            if (apps.size() == AuroraPrefs.MAX_HOME_APPS) break;
            if (!pkg.isEmpty() && !apps.contains(pkg) && launchIntent(pm, pkg) != null) apps.add(pkg);
        }
        return apps;
    }

    private void buildHomeRow() {
        View focused = activity.getCurrentFocus();
        int focusedIndex = focused != null ? appTiles.indexOf(focused) : -1;
        appRow.removeAllViews();
        appTiles.clear();
        List<String> packages = homeApps();
        for (int i = 0; i < packages.size(); i++) {
            appTiles.add(appTile(packages.get(i), 16, appRow, i == packages.size() - 1, true));
        }
        if (focusedIndex >= 0 && !appTiles.isEmpty()) {
            appTiles.get(Math.min(focusedIndex, appTiles.size() - 1)).requestFocus();
        }
    }

    private int gap() {
        return dp(16) - 2 * dp(RingFrame.GAP_DP + RingFrame.STROKE_DP);
    }

    private RingFrame appTile(String pkg, float radius, LinearLayout row, boolean last, boolean onHome) {
        RingFrame tile = new RingFrame(activity).corners(radius, false).aspect(9f / 16f).lift(1.05f, 0f);
        FrameLayout host = new FrameLayout(activity);
        host.setBackgroundColor(TILE);
        tile.content(host);
        bannerInto(host, pkg);
        tile.setOnClickListener(v -> openApp(pkg));
        tile.setOnLongClickListener(v -> {
            showMenu(tile, pkg, onHome);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!last) lp.setMarginEnd(gap());
        row.addView(tile, lp);
        return tile;
    }

    /** Apps: every app on the TV with a launch entry, Home's included, by name. */
    private void buildAllApps() {
        appsBuilt = true;
        LinearLayout grid = activity.findViewById(R.id.aurora_apps_grid);
        while (grid.getChildCount() > 1) grid.removeViewAt(1);   // keep the heading
        List<String[]> apps = allApps();
        gridOf(grid, apps, (row, pkg, last) -> appTile(pkg, 14, row, last, false));
    }

    private interface TileMaker {
        View make(LinearLayout row, String pkg, boolean last);
    }

    private void gridOf(LinearLayout grid, List<String[]> apps, TileMaker maker) {
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % GRID_COLUMNS == 0) {
                row = new LinearLayout(activity, null, 0, R.style.AuroraRow);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = gap();
                grid.addView(row, rowLp);
            }
            maker.make(row, apps.get(i)[1], i % GRID_COLUMNS == GRID_COLUMNS - 1);
        }
        // A short last row keeps the grid's column width.
        int rest = apps.size() % GRID_COLUMNS;
        if (row != null && rest != 0) {
            for (int i = rest; i < GRID_COLUMNS; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                if (i < GRID_COLUMNS - 1) lp.setMarginEnd(gap());
                row.addView(new View(activity), lp);
            }
        }
    }

    /** {label, package} of every launchable app but this launcher, sorted by name. */
    private List<String[]> allApps() {
        PackageManager pm = activity.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[]{label(pm, pkg, ri), pkg});
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a[0], b[0]));
        return apps;
    }

    private String label(PackageManager pm, String pkg, ResolveInfo ri) {
        String label = cfg.appLabels.get(pkg);
        return label != null ? label : String.valueOf(ri.loadLabel(pm));
    }

    /**
     * Fills the tile: the app's TV banner (or a bundled one from app_banners), drawn once into a small bitmap on a
     * background thread and cached; its icon and name on a dark card when it has none. The tile shows its dark
     * colour until then, so building a page never waits on another app's resources.
     */
    private void bannerInto(FrameLayout host, String pkg) {
        Object cached = BANNERS.get(pkg);
        if (cached != null) {
            showBanner(host, pkg, cached);
            return;
        }
        host.setTag(pkg);
        PackageManager pm = activity.getPackageManager();
        Context app = activity.getApplicationContext();
        BANNER_IO.execute(() -> {
            Object result = NO_BANNER;
            try {
                Drawable art = bundledBanner(app, cfg.appBanners.get(pkg));
                if (art == null) {
                    Intent launch = launchIntent(pm, pkg);
                    ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
                    if (ri != null) art = loadBanner(pm, ri.activityInfo);
                }
                if (art != null) result = render(art);
            } catch (RuntimeException ignored) {
            }
            BANNERS.put(pkg, result);
            Object done = result;
            main.post(() -> {
                if (pkg.equals(host.getTag())) showBanner(host, pkg, done);
            });
        });
    }

    private static Bitmap render(Drawable art) {
        Bitmap bitmap = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(TILE);
        // Centre-crop the artwork into 16:9.
        int w = Math.max(1, art.getIntrinsicWidth()), h = Math.max(1, art.getIntrinsicHeight());
        if (art.getIntrinsicWidth() <= 0 || art.getIntrinsicHeight() <= 0) {
            art.setBounds(0, 0, BANNER_W, BANNER_H);
        } else {
            float scale = Math.max(BANNER_W / (float) w, BANNER_H / (float) h);
            int dw = Math.round(w * scale), dh = Math.round(h * scale);
            int left = (BANNER_W - dw) / 2, top = (BANNER_H - dh) / 2;
            art.setBounds(left, top, left + dw, top + dh);
        }
        art.draw(canvas);
        return bitmap;
    }

    private void showBanner(FrameLayout host, String pkg, Object banner) {
        // Only the artwork is replaced; a picker badge over it stays.
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            if ("art".equals(host.getChildAt(i).getTag())) host.removeViewAt(i);
        }
        if (banner instanceof Bitmap) {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap((Bitmap) banner);
            image.setTag("art");
            host.addView(image, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        PackageManager pm = activity.getPackageManager();
        Intent launch = launchIntent(pm, pkg);
        ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        if (ri != null) {
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(ri.loadIcon(pm));
            card.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        }
        TextView name = new TextView(activity, null, 0, R.style.AuroraCardTitle);
        name.setText(ri != null ? label(pm, pkg, ri) : pkg);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(6), dp(5), dp(6), 0);
        card.addView(name);
        card.setTag("art");
        host.addView(card, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static Drawable bundledBanner(Context context, String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        return id != 0 ? context.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ActivityInfo info) {
        int id = info.banner != 0 ? info.banner : info.applicationInfo.banner;
        if (id == 0) return null;
        try {
            Resources res = pm.getResourcesForApplication(info.applicationInfo);
            int dpi = Math.max(DisplayMetrics.DENSITY_XHIGH, activity.getResources().getDisplayMetrics().densityDpi);
            Drawable d = res.getDrawableForDensity(id, dpi, null);
            return d != null && AppAdapter.isBannerShaped(d.getIntrinsicWidth(), d.getIntrinsicHeight()) ? d : null;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
        return tv != null ? tv : pm.getLaunchIntentForPackage(pkg);
    }

    private void openApp(String pkg) {
        Intent launch = launchIntent(activity.getPackageManager(), pkg);
        if (launch == null) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // ---- tile menu (hold OK) ---------------------------------------------------------------------

    private void showMenu(View anchor, String pkg, boolean onHome) {
        closeMenu(false);
        List<String> home = homeApps();
        boolean inHome = home.contains(pkg);
        int index = home.indexOf(pkg);
        boolean removable = false;
        try {
            ApplicationInfo info = activity.getPackageManager().getApplicationInfo(pkg, 0);
            removable = (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161D2C);
        bg.setStroke(dp(0.5f), 0x33FFFFFF);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setElevation(dp(12));

        menuItem(panel, R.string.aurora_menu_open, TXT, () -> openApp(pkg));
        if (inHome) {
            menuItem(panel, R.string.aurora_menu_remove, TXT, () -> {
                home.remove(pkg);
                saveHome(home);
            });
            if (onHome && index > 0) menuItem(panel, R.string.aurora_menu_left, TXT, () -> {
                home.remove(index);
                home.add(index - 1, pkg);
                saveHome(home);
                appTiles.get(index - 1).requestFocus();
            });
            if (onHome && index < home.size() - 1) menuItem(panel, R.string.aurora_menu_right, TXT, () -> {
                home.remove(index);
                home.add(index + 1, pkg);
                saveHome(home);
                appTiles.get(index + 1).requestFocus();
            });
        } else {
            menuItem(panel, R.string.aurora_menu_add, TXT, () -> {
                if (home.size() >= AuroraPrefs.MAX_HOME_APPS) {
                    Toast.makeText(activity, R.string.aurora_pick_full, Toast.LENGTH_SHORT).show();
                    return;
                }
                home.add(pkg);
                saveHome(home);
            });
        }
        menuItem(panel, R.string.aurora_menu_info, TXT, () -> start(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (removable) menuItem(panel, R.string.aurora_menu_uninstall, RED, () -> start(new Intent(
                Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2], base = new int[2];
        anchor.getLocationInWindow(at);
        root.getLocationInWindow(base);
        int x = at[0] - base[0] + dp(RingFrame.GAP_DP + RingFrame.STROKE_DP);
        int y = at[1] - base[1] + anchor.getHeight() + dp(4);
        if (y + panel.getMeasuredHeight() > root.getHeight() - dp(16)) y = at[1] - base[1] - panel.getMeasuredHeight() - dp(4);
        x = Math.min(x, root.getWidth() - panel.getMeasuredWidth() - dp(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = x;
        lp.topMargin = y;
        root.addView(panel, lp);
        menu = panel;
        menuAnchor = anchor;
        if (motion(activity)) {
            panel.setAlpha(0f);
            panel.setTranslationY(-dp(6));
            panel.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        panel.getChildAt(0).requestFocus();
    }

    private void menuItem(LinearLayout panel, int text, int color, Runnable action) {
        TextView item = new TextView(activity, null, 0, R.style.AuroraButtonTitle);
        item.setText(text);
        item.setTextColor(color);
        item.setFocusable(true);
        item.setMinWidth(dp(150));
        item.setPadding(dp(12), dp(8), dp(16), dp(8));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x2EFFFFFF);
        focused.setCornerRadius(dp(10));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        item.setBackground(states);
        item.setOnClickListener(v -> {
            closeMenu(true);
            action.run();
        });
        panel.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** The menu keeps the keys: Up and Down move inside it, Left and Right do nothing, Back closes it. */
    private boolean menuKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!vertical && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (vertical) {
            ViewGroup panel = (ViewGroup) menu;
            View focused = activity.getCurrentFocus();
            int i = focused != null ? panel.indexOfChild(focused) : -1;
            int next = Math.max(0, Math.min(panel.getChildCount() - 1,
                    i + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
            panel.getChildAt(next).requestFocus();
        }
        return true;
    }

    private void closeMenu(boolean refocus) {
        if (menu == null) return;
        root.removeView(menu);
        menu = null;
        if (refocus && menuAnchor != null && menuAnchor.isAttachedToWindow()) menuAnchor.requestFocus();
        menuAnchor = null;
    }

    private void saveHome(List<String> apps) {
        AuroraPrefs.setHomeApps(activity, apps);
        buildHomeRow();
        updateHomeAppsValue();
    }

    // ---- settings --------------------------------------------------------------------------------

    private void buildSettings(LinearLayout page) {
        TextView heading = new TextView(activity, null, 0, R.style.AuroraHeading);
        heading.setText(R.string.aurora_settings);
        page.addView(heading, headingParams(0));

        // System shortcuts: one row of compact tiles, icon beside the name.
        LinearLayout quick = new LinearLayout(activity, null, 0, R.style.AuroraRow);
        Intent[] intents = {
                new Intent(Settings.ACTION_SETTINGS),
                new Intent(Settings.ACTION_WIFI_SETTINGS),
                new Intent(Settings.ACTION_DISPLAY_SETTINGS),
                new Intent("com.android.tv.action.VIEW_INPUTS"),
                new Intent(Settings.ACTION_APPLICATION_SETTINGS),
        };
        int[] icons = {R.drawable.ic_settings, R.drawable.ic_wifi, R.drawable.ic_backlight, R.drawable.ic_input,
                R.drawable.ic_apps};
        int[] labels = {R.string.aurora_set_tv, R.string.aurora_set_wifi, R.string.aurora_set_picture,
                R.string.aurora_set_inputs, R.string.aurora_set_apps};
        for (int i = 0; i < intents.length; i++) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), 0, dp(10), 0);
            card.setBackgroundResource(R.drawable.aurora_row);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(TXT);
            card.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView name = new TextView(activity, null, 0, R.style.AuroraCardTitle);
            name.setText(labels[i]);
            name.setPadding(dp(10), 0, 0, 0);
            card.addView(name);
            RingFrame tile = new RingFrame(activity).corners(12, false).lift(1.03f, 0f);
            tile.content(card);
            Intent intent = intents[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean display = i == 2;
            tile.setOnClickListener(v -> {
                try {
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException e) {
                    // Picture & sound falls back to the TV's Settings where the display action has no screen.
                    if (display) start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    else Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    dp(48) + 2 * dp(RingFrame.GAP_DP + RingFrame.STROKE_DP), 1f);
            if (i < intents.length - 1) lp.setMarginEnd(gap());
            quick.addView(tile, lp);
        }
        page.addView(quick);

        TextView home = new TextView(activity, null, 0, R.style.AuroraHeading);
        home.setText(R.string.aurora_home_screen);
        page.addView(home, headingParams(22));

        // Home screen options: a column of rows, label and hint on the left, value or switch on the right.
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(dp(51.5f), 0, dp(51.5f), 0);
        homeAppsValue = new TextView(activity, null, 0, R.style.AuroraButtonSub);
        settingsRow(list, R.string.aurora_apps_on_home, R.string.aurora_apps_on_home_hint, homeAppsValue, null,
                v -> openPicker());
        updateHomeAppsValue();
        toggleRow(list, R.string.aurora_watch_toggle, R.string.aurora_watch_hint, "watch_next", false, on -> refreshWatching());
        toggleRow(list, R.string.aurora_above_toggle, R.string.aurora_above_hint, "watch_above", false,
                on -> refreshWatching());
        toggleRow(list, R.string.aurora_posters_toggle, R.string.aurora_posters_hint, "show_posters", true,
                on -> refreshWatching());
        if (govee != null) {
            AuroraSwitch auto = toggleRow(list, R.string.aurora_light_toggle, R.string.aurora_light_hint, "govee_auto",
                    AuroraPrefs.goveeAuto(activity), on -> {
                        GoveeAuto.stop(activity);
                        if (on) GoveeAuto.start(activity);
                    });
            auto.setChecked(AuroraPrefs.goveeAuto(activity), false);
        }
        page.addView(list);

        TextView look = new TextView(activity, null, 0, R.style.AuroraHeading);
        look.setText(R.string.aurora_look);
        page.addView(look, headingParams(22));
        LinearLayout lookList = new LinearLayout(activity);
        lookList.setOrientation(LinearLayout.VERTICAL);
        lookList.setClipChildren(false);
        lookList.setPadding(dp(51.5f), 0, dp(51.5f), 0);
        TextView styleValue = new TextView(activity, null, 0, R.style.AuroraButtonSub);
        paintFocusStyle(styleValue);
        settingsRow(lookList, R.string.aurora_focus_style, R.string.aurora_focus_hint, styleValue, null, v -> {
            boolean glide = !FocusGlide.enabled(activity);
            AuroraPrefs.setText(activity, "focus_style", glide ? FocusGlide.GLIDE : FocusGlide.LIFT);
            paintFocusStyle(styleValue);
            ((RingFrame) v).restyle();
        });
        page.addView(lookList);

        TextView background = new TextView(activity, null, 0, R.style.AuroraHeading);
        background.setText(R.string.aurora_background);
        page.addView(background, headingParams(22));
        LinearLayout skies = new LinearLayout(activity);
        skies.setOrientation(LinearLayout.VERTICAL);
        skies.setClipChildren(false);
        buildSkies(skies);
        page.addView(skies);
    }

    private void paintFocusStyle(TextView value) {
        value.setText(FocusGlide.enabled(activity) ? R.string.aurora_focus_glide : R.string.aurora_focus_lift);
    }

    /** The backdrops as small previews, the chosen one marked with a dot; OK switches the screen's sky at once. */
    private void buildSkies(LinearLayout grid) {
        List<View> dots = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < AuroraSky.SKIES.length; i++) {
            AuroraSky.Sky s = AuroraSky.SKIES[i];
            if (i % GRID_COLUMNS == 0) {
                row = new LinearLayout(activity, null, 0, R.style.AuroraRow);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = dp(6);
                grid.addView(row, rowLp);
            }
            FrameLayout host = new FrameLayout(activity);
            host.addView(new AuroraSky(activity, s.key), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View dot = new View(activity);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0xFFFFFFFF);
            dot.setBackground(shape);
            dot.setTag(s.key);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP | Gravity.END);
            dotLp.setMargins(0, dp(7), dp(7), 0);
            host.addView(dot, dotLp);
            dots.add(dot);
            RingFrame tile = new RingFrame(activity).corners(12, false).aspect(9f / 16f).lift(1.05f, 0f);
            tile.content(host);
            tile.setOnClickListener(v -> {
                AuroraPrefs.setText(activity, "background", s.key);
                sky.show(s.key);
                paintSkyDots(dots);
            });

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipChildren(false);
            column.addView(tile);
            TextView name = new TextView(activity, null, 0, R.style.AuroraCardSub);
            name.setText(s.name);
            name.setPadding(dp(4.5f), dp(2), dp(4.5f), 0);
            column.addView(name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % GRID_COLUMNS != GRID_COLUMNS - 1) lp.setMarginEnd(gap());
            row.addView(column, lp);
        }
        paintSkyDots(dots);
    }

    private void paintSkyDots(List<View> dots) {
        String chosen = AuroraSky.find(AuroraPrefs.text(activity, "background", AuroraSky.SKIES[0].key)).key;
        for (View dot : dots) dot.setVisibility(chosen.equals(dot.getTag()) ? View.VISIBLE : View.GONE);
    }

    private LinearLayout.LayoutParams headingParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(56));
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private interface Toggled {
        void changed(boolean on);
    }

    private AuroraSwitch toggleRow(LinearLayout list, int label, int hint, String key, boolean fallback, Toggled toggled) {
        AuroraSwitch sw = new AuroraSwitch(activity);
        sw.setChecked(AuroraPrefs.flag(activity, key, fallback), false);
        settingsRow(list, label, hint, null, sw, v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on, true);
            AuroraPrefs.setFlag(activity, key, on);
            toggled.changed(on);
        });
        return sw;
    }

    private void settingsRow(LinearLayout list, int label, int hint, TextView value, AuroraSwitch sw,
                             View.OnClickListener click) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 0);
        body.setBackgroundResource(R.drawable.aurora_row);
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity, null, 0, R.style.AuroraCardTitle);
        title.setText(label);
        title.setTextSize(12.5f);
        text.addView(title);
        if (hint != 0) {
            TextView sub = new TextView(activity, null, 0, R.style.AuroraCardSub);
            sub.setText(hint);
            sub.setPadding(0, dp(2), 0, 0);
            text.addView(sub);
        }
        body.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) body.addView(value);
        if (sw != null) body.addView(sw, new LinearLayout.LayoutParams(dp(36), dp(20)));
        RingFrame row = new RingFrame(activity).corners(12, false).lift(1f, 0);
        row.content(body);
        row.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52) + 2 * dp(RingFrame.GAP_DP + RingFrame.STROKE_DP));
        if (list.getChildCount() > 0) lp.topMargin = dp(12) - 2 * dp(RingFrame.GAP_DP + RingFrame.STROKE_DP);
        list.addView(row, lp);
    }

    private void updateHomeAppsValue() {
        if (homeAppsValue == null) return;
        PackageManager pm = activity.getPackageManager();
        List<String> names = new ArrayList<>();
        for (String pkg : homeApps()) {
            Intent launch = launchIntent(pm, pkg);
            ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
            names.add(ri != null ? label(pm, pkg, ri) : pkg);
        }
        homeAppsValue.setText(names.isEmpty() ? "—" : TextUtils.join(", ", names));
        homeAppsValue.setMaxWidth(dp(420));
        homeAppsValue.setEllipsize(TextUtils.TruncateAt.END);
    }

    // ---- apps on Home picker -----------------------------------------------------------------------

    private void openPicker() {
        LinearLayout grid = activity.findViewById(R.id.aurora_picker_grid);
        grid.removeAllViews();
        TextView heading = new TextView(activity, null, 0, R.style.AuroraHeading);
        heading.setText(R.string.aurora_apps_on_home);
        grid.addView(heading, headingParams(0));
        TextView note = new TextView(activity, null, 0, R.style.AuroraCardSub);
        note.setText(R.string.aurora_pick_note);
        LinearLayout.LayoutParams noteLp = headingParams(0);
        noteLp.topMargin = -dp(4);
        noteLp.bottomMargin = dp(12);
        grid.addView(note, noteLp);
        List<String> chosen = homeApps();
        List<TextView> badges = new ArrayList<>();
        List<String> order = new ArrayList<>();
        gridOf(grid, allApps(), (row, pkg, last) -> {
            RingFrame tile = new RingFrame(activity).corners(14, false).aspect(9f / 16f).lift(1.05f, 0f);
            FrameLayout host = new FrameLayout(activity);
            host.setBackgroundColor(TILE);
            tile.content(host);
            bannerInto(host, pkg);
            TextView badge = new TextView(activity, null, 0, R.style.AuroraButtonTitle);
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(INK);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFFFFFFF);
            badge.setBackground(dot);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            blp.setMargins(0, dp(6), dp(6), 0);
            badge.setElevation(dp(2));
            // The badge sits over the banner, which arrives later and is added at index 0.
            host.addView(badge, blp);
            badges.add(badge);
            order.add(pkg);
            tile.setOnClickListener(v -> {
                if (chosen.contains(pkg)) {
                    chosen.remove(pkg);
                } else if (chosen.size() >= AuroraPrefs.MAX_HOME_APPS) {
                    Toast.makeText(activity, R.string.aurora_pick_full, Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    chosen.add(pkg);
                }
                AuroraPrefs.setHomeApps(activity, chosen);
                paintBadges(badges, order, chosen);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (!last) lp.setMarginEnd(gap());
            row.addView(tile, lp);
            return tile;
        });
        paintBadges(badges, order, chosen);
        crossFade(settingsScroll, picker);
        picker.scrollTo(0, 0);
        View first = firstFocusable(grid);
        if (first != null) first.requestFocus();
    }

    private static void paintBadges(List<TextView> badges, List<String> order, List<String> chosen) {
        for (int i = 0; i < badges.size(); i++) {
            int at = chosen.indexOf(order.get(i));
            badges.get(i).setVisibility(at >= 0 ? View.VISIBLE : View.GONE);
            badges.get(i).setText(at >= 0 ? String.valueOf(at + 1) : "");
        }
    }

    private void closePicker(boolean refocus) {
        buildHomeRow();
        updateHomeAppsValue();
        crossFade(picker, settingsScroll);
        if (refocus) {
            View first = firstFocusable((ViewGroup) settingsScroll);
            // The "Apps on Home" row is the first row after the quick tiles.
                ArrayList<View> all = new ArrayList<>();
            ((ViewGroup) settingsScroll).addFocusables(all, View.FOCUS_DOWN);
            View row = all.size() > 5 ? all.get(5) : first;
            if (row != null) row.requestFocus();
        }
    }

    // ---- Continue watching (Nuvio) ---------------------------------------------------------------

    private void refreshWatching() {
        boolean on = AuroraPrefs.flag(activity, "watch_next", false);
        placeHomeRow(on);
        if (!on) {
            showWatching(new ArrayList<>());
            return;
        }
        String pkg = fork.optString("watch_next_app", NUVIO);
        WatchNext.load(activity, pkg, WATCH_PORTRAIT, AuroraPrefs.flag(activity, "show_posters", true),
                this::showWatching);
    }

    /**
     * Without Continue watching the apps sit in the lower part of the screen. With it, they lead under the bar, or,
     * with watch_above, stay where they are and the row of what you are watching sits over them.
     */
    private void placeHomeRow(boolean watching) {
        LinearLayout page = (LinearLayout) pages[HOME];
        boolean top = watching && !watchAbove();
        page.setGravity(top ? Gravity.TOP : Gravity.BOTTOM);
        page.setPadding(0, 0, 0, top ? 0 : dp(75));
        View appsTitle = activity.findViewById(R.id.aurora_apps_title);
        View[] order = watching && !top ? new View[]{watchTitle, watchRow, appsTitle, appRow}
                : new View[]{appsTitle, appRow, watchTitle, watchRow};
        if (page.getChildAt(0) != order[0]) {
            View focused = activity.getCurrentFocus();
            for (View v : order) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                page.removeView(v);
                page.addView(v, lp);
            }
            // The second section keeps its distance from the first.
            ((LinearLayout.LayoutParams) order[0].getLayoutParams()).topMargin = 0;
            ((LinearLayout.LayoutParams) order[2].getLayoutParams()).topMargin = dp(order[2] == appsTitle ? 16 : 22);
            if (focused != null && isInside(focused, page)) focused.requestFocus();
        }
        // The padding is room, not a clip: the lifted tile and its ring may draw into it.
        page.setClipToPadding(false);
    }

    private boolean watchAbove() {
        return AuroraPrefs.flag(activity, "watch_above", false);
    }

    /** One format for the whole row: the format most of the posters have. */
    private void showWatching(List<WatchNext.Item> items) {
        View focused = activity.getCurrentFocus();
        boolean hadFocus = focused != null && isInside(focused, watchRow);
        watchRow.removeAllViews();
        boolean show = !items.isEmpty();
        watchTitle.setVisibility(show ? View.VISIBLE : View.GONE);
        watchRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            if (hadFocus) focusFirst(HOME);
            return;
        }
        int portrait = 0;
        for (WatchNext.Item item : items) if (item.portrait) portrait++;
        boolean posters = portrait * 2 >= items.size();
        // Over the apps the posters are smaller, so both rows fit under the bar.
        int slots = posters ? (watchAbove() ? WATCH_PORTRAIT_ABOVE : WATCH_PORTRAIT) : WATCH_LANDSCAPE;
        int count = Math.min(items.size(), slots);
        for (int i = 0; i < slots; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < slots - 1) lp.setMarginEnd(gap());
            if (i >= count) {
                // An empty slot keeps the column width but no height: a bare View would take all the room left.
                lp.height = 0;
                watchRow.addView(new View(activity), lp);
                continue;
            }
            watchRow.addView(posters ? posterCard(items.get(i)) : wideCard(items.get(i)), lp);
        }
        if (hadFocus) {
            View first = firstFocusable(watchRow);
            if (first != null) first.requestFocus();
        }
    }

    /** 2:3: the poster with its progress, the title and what is left under it. */
    private View posterCard(WatchNext.Item item) {
        FrameLayout art = new FrameLayout(activity);
        art.setBackgroundColor(TILE);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        art.addView(progress(item.progress), progressParams(7));
        RingFrame frame = new RingFrame(activity).corners(12, false).aspect(1.5f).lift(1.05f, 0f);
        frame.content(art);
        frame.setOnClickListener(v -> openWatching(item));
        WatchNext.poster(image, item.poster, dp(110) * 2);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.addView(frame);
        TextView title = new TextView(activity, null, 0, R.style.AuroraCardTitle);
        title.setText(item.title);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4.5f), dp(4), dp(4.5f), 0);
        TextView sub = new TextView(activity, null, 0, R.style.AuroraCardSub);
        sub.setText(item.subtitle);
        sub.setPadding(dp(4.5f), dp(2), dp(4.5f), 0);
        column.addView(title);
        column.addView(sub);
        return column;
    }

    /** 16:9: the still with a dark scrim, the title, what is left and the progress over it. */
    private View wideCard(WatchNext.Item item) {
        FrameLayout art = new FrameLayout(activity);
        art.setBackgroundColor(TILE);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00040710, 0xEB040710}));
        art.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity, null, 0, R.style.AuroraCardTitle);
        title.setText(item.title);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = new TextView(activity, null, 0, R.style.AuroraCardSub);
        sub.setText(item.subtitle);
        text.addView(title);
        text.addView(sub);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        textLp.setMargins(dp(11), 0, dp(11), dp(16));
        art.addView(text, textLp);
        art.addView(progress(item.progress), progressParams(10));
        RingFrame frame = new RingFrame(activity).corners(16, false).aspect(9f / 16f).lift(1.05f, 0f);
        frame.content(art);
        frame.setOnClickListener(v -> openWatching(item));
        WatchNext.poster(image, item.poster, dp(200) * 2);
        return frame;
    }

    private View progress(float value) {
        LinearLayout track = new LinearLayout(activity);
        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setColor(0x38FFFFFF);
        trackShape.setCornerRadius(dp(2));
        track.setBackground(trackShape);
        View fill = new View(activity);
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setColor(0xFF5FD3C4);
        fillShape.setCornerRadius(dp(2));
        fill.setBackground(fillShape);
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(0.02f, value)));
        track.addView(new View(activity), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(0f, 1f - value)));
        return track;
    }

    private FrameLayout.LayoutParams progressParams(float marginDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3),
                Gravity.BOTTOM);
        lp.setMargins(dp(marginDp), 0, dp(marginDp), dp(marginDp));
        return lp;
    }

    private void openWatching(WatchNext.Item item) {
        if (item.open == null || !start(item.open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) openApp(NUVIO);
    }

    // ---- lifecycle, from MainActivity ------------------------------------------------------------

    void onStart() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        vpnCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                activity.runOnUiThread(AuroraHome.this::refreshVpn);
            }

            @Override
            public void onLost(Network network) {
                activity.runOnUiThread(AuroraHome.this::refreshVpn);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(vpnCallback);
        } catch (RuntimeException e) {
            vpnCallback = null;
        }
    }

    void onResume() {
        resumed = true;
        wallpaper.start();
        refreshVpn();
        main.removeCallbacks(lightPoll);
        lightPoll.run();
        refreshWatching();
        // An app installed, updated or removed while Home was away: rebuild what lists apps.
        ChangedPackages changes = activity.getPackageManager().getChangedPackages(packageSequence);
        if (changes != null) {
            packageSequence = changes.getSequenceNumber();
            for (String pkg : changes.getPackageNames()) BANNERS.remove(pkg);
            buildHomeRow();
            updateHomeAppsValue();
            if (appsBuilt) buildAllApps();
        }
    }

    void onPause() {
        resumed = false;
        main.removeCallbacks(lightPoll);
        closeMenu(false);
        wallpaper.stop();
    }

    void onStop() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm != null && vpnCallback != null) {
            try {
                cm.unregisterNetworkCallback(vpnCallback);
            } catch (RuntimeException ignored) {
            }
        }
        vpnCallback = null;
    }

    void onTrimMemory() {
        wallpaper.releaseForMemoryPressure();
    }

    void onDestroy() {
        wallpaper.close();
    }

    /** Home pressed while Home is in front: the Home page with the first app focused. */
    void home() {
        closeMenu(false);
        if (selected != HOME) {
            tabs[HOME].requestFocus();
            select(HOME);
        }
        tabs[HOME].post(() -> focusFirst(HOME));
    }

    /** Back: closes the menu or the picker; from Apps or Settings to Home; on Home, to the first app. */
    void back() {
        if (menu != null) {
            closeMenu(true);
        } else if (selected == SETTINGS && picker.getVisibility() == View.VISIBLE) {
            closePicker(true);
        } else if (selected != HOME) {
            tabs[HOME].requestFocus();
        } else {
            focusFirst(HOME);
        }
    }

    void onNewIntent(Intent intent) {
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) home();
    }
}
