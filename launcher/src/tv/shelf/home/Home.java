package tv.shelf.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Shelf home screen: CleanFrame's look (a photograph, the time, three quiet icons and a glass
 * dock) with an Apple TV-style grid. The first row of apps sits on the glass dock; the rest follow
 * in rows underneath, and scrolling down blurs and darkens the photo behind them.
 *
 * Forked from dumbtv home (MIT). Its Home-button handling ({@link Guard}, {@link Keeper}), app
 * catalog, recent ordering, tile menu and input switching are kept as they were.
 */
public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Horizontal gap between tiles, and vertical gap between grid rows, in dp. */
    private static final int GAP = 22, ROW_GAP = 30;
    /** Padding inside the glass dock around its row of tiles, in dp. */
    private static final int DOCK_PAD = 20;
    /** Where a focused grid row settles, measured from the top of the screen, in dp. */
    private static final int ROW_STOP = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<ImageView> utilButtons = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration, columns = 5, lastDock;
    private boolean resumed, landed, unlockOpened, scrolled;
    private String bgKey;

    private FrameLayout root;
    private ImageView photo, soft;
    private View dim, veil, topShade, scrim;
    private ScrollView page;
    private LinearLayout column, strip;
    private View spacer;
    private Glass dock;
    private HeaderFade headerFade;
    private TextView moreTitle, inputsTitle, clock, utilLabel;
    private Grid more, inputGrid;
    private LinearLayout utils;
    private Meter meter;

    private List<Item> appsAll = new ArrayList<>();
    private List<Item> inputsAll = new ArrayList<>();
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.fonts(this);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        Future<Map<String, Long>> times = recentFor(Catalog.APPS) == null ? null
                : io.submit(() -> Recent.times(this));
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        inputsAll = Catalog.inputs(this, tv);
    }

    /**
     * Waits for the app list and loads the art of the first two rows, so the first frame is
     * complete instead of popping banners in after a cold start.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        try {
            appsAll = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = columnsFor(cardSize()[0]) * 2;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, appsAll, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it, "afterglow".equals(cfg.get(Config.TILE_STYLE)));
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else applyBackground();
        refreshRecent();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB) unless memory is critical.
     * Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int step = Catalog.APPS.equals(moving.kind) ? columns : Math.max(1, inputGrid.columns());
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    shift(-step);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    shift(step);
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- building the screen

    private View build() {
        root = new FrameLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                View v = route(focused, direction);
                return v != null ? v : super.focusSearch(focused, direction);
            }
        };
        photo = image();
        soft = image();
        soft.setAlpha(0f);
        dim = new View(this);
        veil = new View(this);
        veil.setBackgroundColor(0x6B060A0C);
        veil.setAlpha(0f);
        topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x6B040A0C, 0x33040A0C, 0x00040A0C}));
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00040A0C, 0x4D040A0C, 0x85040A0C}));
        root.addView(photo, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(soft, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(veil, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(topShade, new FrameLayout.LayoutParams(MATCH, dp(150), Gravity.TOP));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, dp(260), Gravity.BOTTOM));

        page = new ScrollView(this) {
            /** Row 1 keeps the photo in view; any lower row settles near the top of the screen. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                int target = r.top < dock.getBottom() ? 0 : r.top - dp(ROW_STOP);
                int range = Math.max(0, getChildAt(0).getHeight() - getHeight());
                return Math.max(0, Math.min(range, target)) - getScrollY();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                dock.invalidate();
                setScrolled(t > dp(40));
            }

            /** Up and down only move the focus; scrolling follows it, a whole row at a time. */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        page.setFocusable(false);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setClipChildren(false);
        page.setSmoothScrollingEnabled(true);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
        headerFade = new HeaderFade(this, root);
        headerFade.setAlpha(0f);
        root.addView(headerFade, new FrameLayout.LayoutParams(MATCH, dp(ROW_STOP - 15), Gravity.TOP));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        page.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(MATCH, dp(320)));
        dock = new Glass(this, root);
        strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(DOCK_PAD), dp(DOCK_PAD), dp(DOCK_PAD), 0);
        dock.addView(strip, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(dp(848), dp(146));
        dockLp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(dock, dockLp);

        moreTitle = sectionTitle("More apps");
        more = new Grid(this, GAP, ROW_GAP);
        column.addView(moreTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        column.addView(more, new LinearLayout.LayoutParams(MATCH, WRAP));
        inputsTitle = sectionTitle("Inputs");
        inputGrid = new Grid(this, GAP, ROW_GAP);
        column.addView(inputsTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        column.addView(inputGrid, new LinearLayout.LayoutParams(MATCH, WRAP));

        // The time, top left, and the quiet utility icons, top right: they stay put while the
        // page scrolls underneath (CleanFrame's caption and utilities).
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 24, Ui.TEXT, Ui.MEDIUM);
        clock.setLetterSpacing(-0.03f);
        clock.setShadowLayer(dp(2), 0, dp(1), 0x38000000);
        when.addView(clock);
        meter = new Meter(this);
        meter.setVisibility(View.GONE);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(WRAP, WRAP);
        under.topMargin = dp(8);
        when.addView(meter, under);
        FrameLayout.LayoutParams whenLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        whenLp.leftMargin = dp(70);
        whenLp.topMargin = dp(36);
        root.addView(when, whenLp);

        utils = new LinearLayout(this);
        utils.addView(util(R.drawable.ic_photo, "Wallpaper", v -> nextPhoto()));
        utils.addView(util(R.drawable.ic_options, "Options", v -> startActivity(new Intent(this, Options.class))));
        utils.addView(util(R.drawable.ic_settings, "Settings", v -> openSettings()));
        FrameLayout.LayoutParams utilsLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        utilsLp.rightMargin = dp(61);
        utilsLp.topMargin = dp(36);
        root.addView(utils, utilsLp);
        utilLabel = Ui.text(this, 14, 0xFFF6F1E7, Ui.MEDIUM);
        utilLabel.setGravity(Gravity.CENTER);
        utilLabel.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        utilLabel.setAlpha(0f);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(dp(160), WRAP, Gravity.TOP | Gravity.START);
        labelLp.topMargin = dp(72);
        root.addView(utilLabel, labelLp);
        return root;
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView sectionTitle(String name) {
        TextView t = Ui.text(this, 14, 0xB8F6F1E7, Ui.SEMIBOLD);
        t.setText(name);
        t.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        return t;
    }

    /** One quiet utility icon: two-thirds opacity at rest, full and slightly larger with focus. */
    private ImageView util(int icon, String name, View.OnClickListener click) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setPadding(dp(7), dp(7), dp(7), dp(7));
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(name);
        v.setAlpha(0.66f);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().alpha(has ? 1f : 0.66f).scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(140).start();
            if (has) {
                utilLabel.setText(name);
                float centre = utils.getLeft() + view.getLeft() + view.getWidth() / 2f;
                utilLabel.setTranslationX(centre - dp(80));
            }
            utilLabel.animate().alpha(has ? 1f : 0f).setDuration(120).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        utilButtons.add(v);
        return v;
    }

    // ---------------------------------------------------------------- remote navigation

    /**
     * Where the D-pad goes, so focus never jumps somewhere surprising: left and right stop at
     * the end of a row, up and down keep the column, the dock and the grid behave as one grid,
     * and the utility icons are reached by going up from the dock.
     */
    private View route(View focused, int dir) {
        int u = utilButtons.indexOf(focused);
        if (u >= 0) {
            if (dir == View.FOCUS_LEFT) return u > 0 ? utilButtons.get(u - 1) : focused;
            if (dir == View.FOCUS_RIGHT) return u < utilButtons.size() - 1 ? utilButtons.get(u + 1) : focused;
            if (dir == View.FOCUS_UP) return focused;
            if (dir == View.FOCUS_DOWN) return firstTile(Math.min(lastDock, Math.max(0, strip.getChildCount() - 1)));
            return null;
        }
        if (!(focused instanceof Tile) || !(focused.getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) focused.getParent();
        int idx = parent.indexOfChild(focused), count = parent.getChildCount();
        if (parent == strip) {
            if (dir == View.FOCUS_LEFT && idx == 0) return focused;
            if (dir == View.FOCUS_RIGHT && idx == count - 1) return focused;
            if (dir == View.FOCUS_UP) return locked() ? focused : utilButtons.get(utilButtons.size() - 1);
            if (dir == View.FOCUS_DOWN) {
                if (more.getChildCount() > 0) return more.getChildAt(Math.min(idx, more.getChildCount() - 1));
                if (inputGrid.getChildCount() > 0) return inputGrid.getChildAt(0);
                return focused;
            }
            return null;
        }
        if (parent != more && parent != inputGrid) return null;
        int cols = ((Grid) parent).columns(), col = idx % cols, line = idx / cols, lastLine = (count - 1) / cols;
        if (dir == View.FOCUS_LEFT && col == 0) return focused;
        if (dir == View.FOCUS_RIGHT && (col == cols - 1 || idx == count - 1)) return focused;
        if (dir == View.FOCUS_UP) {
            if (line > 0) return parent.getChildAt(idx - cols);
            if (parent == inputGrid && more.getChildCount() > 0) {
                int mc = more.columns(), start = ((more.getChildCount() - 1) / mc) * mc;
                return more.getChildAt(Math.min(start + col, more.getChildCount() - 1));
            }
            if (strip.getChildCount() > 0) return strip.getChildAt(Math.min(col, strip.getChildCount() - 1));
            return focused;
        }
        if (dir == View.FOCUS_DOWN) {
            if (idx + cols < count) return parent.getChildAt(idx + cols);
            if (line < lastLine) return parent.getChildAt(count - 1);
            if (parent == more && inputGrid.getChildCount() > 0) {
                return inputGrid.getChildAt(Math.min(col, inputGrid.getChildCount() - 1));
            }
            return focused;
        }
        return null;
    }

    /** The dock tile at {@code i}, or the first tile anywhere, or a utility icon. */
    private View firstTile(int i) {
        if (strip.getChildCount() > i) return strip.getChildAt(i);
        if (more.getChildCount() > 0) return more.getChildAt(0);
        if (inputGrid.getChildCount() > 0) return inputGrid.getChildAt(0);
        return utilButtons.get(0);
    }

    private void focusFirst() {
        page.scrollTo(0, 0);
        landed = strip.getChildCount() > 0;
        lastDock = 0;
        firstTile(0).requestFocus();
    }

    // ---------------------------------------------------------------- applying settings

    private void apply() {
        applied = cfg.version();
        if (moving != null) stopMoving();
        boolean lockedNow = locked();
        utils.setVisibility(lockedNow ? View.GONE : View.VISIBLE);
        meter.setVisibility("on".equals(cfg.get(Config.MEMORY)) ? View.VISIBLE : View.GONE);
        meter.setAccent(cfg.accent());
        layoutSizes();
        renderApps();
        renderInputs();
        applyBackground();
        tick();
    }

    /** Fits the dock and grid to the tile size: the dock holds exactly one grid row. */
    private void layoutSizes() {
        int[] card = cardSize();
        columns = columnsFor(card[0]);
        int rowW = columns * card[0] + (columns - 1) * dp(GAP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int labelH = "afterglow".equals(cfg.get(Config.TILE_STYLE)) ? 0 : dp(32);
        int dockH = dp(DOCK_PAD) + card[1] + labelH + dp(13);

        ViewGroup.LayoutParams s = spacer.getLayoutParams();
        s.height = Math.max(dp(150), screenH - dockH - dp(74));
        spacer.setLayoutParams(s);
        ViewGroup.LayoutParams d = dock.getLayoutParams();
        d.width = rowW + 2 * dp(DOCK_PAD);
        d.height = dockH;
        dock.setLayoutParams(d);

        int side = Math.max(0, (screenW - rowW) / 2);
        for (TextView t : new TextView[] {moreTitle, inputsTitle}) t.setPadding(side + dp(2), dp(12), side, dp(12));
        more.setPadding(side, 0, side, 0);
        inputGrid.setPadding(side, 0, side, dp(8));
        // Room below the last row so it too can scroll up to the row stop.
        column.setPadding(0, 0, 0, Math.max(dp(32), screenH - dp(ROW_STOP) - card[1] - labelH));
    }

    private int columnsFor(int cardW) {
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * dp(76);
        return Math.max(1, (avail + dp(GAP)) / (cardW + dp(GAP)));
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(116), dp(65)};
            case "large":
                return new int[] {dp(180), dp(101)};
            default:
                return new int[] {dp(144), dp(81)};
        }
    }

    /**
     * Shows the photo for the current Background setting, makes its blurred copy for the glass
     * and the scrolled state, or falls back to a plain colour. Off the UI thread; skipped when
     * nothing changed, so the clock can call it every minute for Wallpaper rotation.
     */
    private void applyBackground() {
        String mode = cfg.get(Config.BACKGROUND);
        File file = "image".equals(mode) ? cfg.wallpaper() : null;
        String key = mode + ":" + ("photo".equals(mode) ? cfg.get(Config.PHOTO)
                : file == null ? "" : file.getPath() + ":" + file.lastModified());
        int dimPct = "image".equals(mode) ? Integer.parseInt(cfg.get(Config.DIM)) : 0;
        dim.setBackgroundColor(Color.argb(dimPct * 255 / 100, 0, 0, 0));
        if (key.equals(bgKey)) return;
        bgKey = key;
        root.setBackgroundColor(cfg.background());
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        io.execute(() -> {
            Bitmap p = Backdrop.photo(this, cfg, w, h);
            Bitmap s = Backdrop.blur(p);
            ui.post(() -> {
                if (!key.equals(bgKey) || isDestroyed()) return;
                photo.setImageBitmap(p);
                soft.setImageBitmap(s);
                dock.setSoft(s);
                headerFade.set(s, 0x6B060A0C, cfg.background());
                int shades = p == null ? View.GONE : View.VISIBLE;
                topShade.setVisibility(shades);
                scrim.setVisibility(shades);
                setScrolled(scrolled, true);
            });
        });
    }

    /** The Wallpaper icon: the next built-in photo, or the next of your own images. */
    private void nextPhoto() {
        String mode = cfg.get(Config.BACKGROUND);
        if ("image".equals(mode)) {
            cfg.nextWallpaper();
        } else if ("photo".equals(mode)) {
            String[] all = Config.PHOTO.values;
            int i = java.util.Arrays.asList(all).indexOf(cfg.get(Config.PHOTO));
            cfg.set(Config.PHOTO.key, all[(i + 1) % all.length]);
        } else {
            cfg.set(Config.BACKGROUND.key, "photo");
        }
        applied = cfg.version();
        applyBackground();
        String name = "photo".equals(cfg.get(Config.BACKGROUND)) ? Config.PHOTO.name(cfg.get(Config.PHOTO)) : "Next image";
        utilLabel.setText(name);
    }

    private void setScrolled(boolean on) {
        setScrolled(on, false);
    }

    /** Below the first row the photo softens and darkens so the grid stays readable. */
    private void setScrolled(boolean on, boolean force) {
        if (on == scrolled && !force) return;
        scrolled = on;
        boolean hasPhoto = photo.getDrawable() != null;
        float softTo = on && hasPhoto ? 1f : 0f;
        float veilTo = on ? 1f : 0f;
        soft.animate().alpha(softTo).setDuration(360).start();
        veil.animate().alpha(veilTo).setDuration(360).start();
        scrim.animate().alpha(on ? 0f : 1f).setDuration(360).start();
        headerFade.animate().alpha(veilTo).setDuration(on ? 200 : 120).start();
    }

    // ---------------------------------------------------------------- tiles

    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : appsAll) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                        }
                    }
                }
                appsAll = list;
                renderApps();
            });
        });
    }

    private void loadInputs() {
        inputsAll = Catalog.inputs(this, tv);
        renderInputs();
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && "recent".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps on the way back home. */
    private void refreshRecent() {
        if (recentFor(Catalog.APPS) == null) return;
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed() || recentFor(Catalog.APPS) == null) return;
                List<Item> before = Catalog.visible(cfg, Catalog.APPS, appsAll, recent);
                recent = times;
                if (!before.equals(Catalog.visible(cfg, Catalog.APPS, appsAll, recent))) renderApps();
            });
        });
    }

    /** The first row goes on the dock, the rest into the grid below it. */
    private void renderApps() {
        String keep = focusedId(Catalog.APPS);
        strip.removeAllViews();
        more.removeAllViews();
        List<Item> shown = Catalog.visible(cfg, Catalog.APPS, appsAll, recentFor(Catalog.APPS));
        boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
        View restore = null;
        for (int i = 0; i < shown.size(); i++) {
            Item it = shown.get(i);
            Tile t = tile(it, Catalog.APPS);
            if (i < columns) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                if (i < columns - 1) lp.rightMargin = dp(GAP);
                strip.addView(t, lp);
            } else {
                more.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            }
            if (it.id.equals(keep)) restore = t;
            if (!it.artRequested || cornerIcon && it.icon == null) loadArt(it);
        }
        dock.setVisibility(shown.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        moreTitle.setVisibility(more.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        settleFocus(restore, keep);
    }

    private void renderInputs() {
        String keep = focusedId(Catalog.INPUTS);
        inputGrid.removeAllViews();
        boolean on = "on".equals(cfg.get(Config.INPUTS));
        View restore = null;
        if (on) {
            for (Item it : Catalog.visible(cfg, Catalog.INPUTS, inputsAll)) {
                Tile t = tile(it, Catalog.INPUTS);
                inputGrid.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
                if (it.id.equals(keep)) restore = t;
            }
        }
        inputsTitle.setVisibility(inputGrid.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        settleFocus(restore, keep);
    }

    private void settleFocus(View restore, String keep) {
        if (restore != null) {
            restore.requestFocus();
        } else if (keep != null || getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private String focusedId(String kind) {
        View f = getCurrentFocus();
        return f instanceof Tile && ((Tile) f).kind.equals(kind) ? ((Tile) f).item.id : null;
    }

    private Tile tile(Item it, String kind) {
        int[] size = cardSize();
        Tile t = new Tile(this, it, kind, Catalog.label(cfg, it), size[0], size[1], cfg.get(Config.LABELS), cfg.accent());
        t.setOnClickListener(v -> open((Tile) v));
        t.setOnFocusChangeListener((v, has) -> {
            if (has && v.getParent() == strip) lastDock = strip.indexOfChild(v);
        });
        t.setOnLongClickListener(v -> {
            if (!locked()) menu((Tile) v);
            return true;
        });
        return t;
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
        io.execute(() -> {
            Catalog.art(getPackageManager(), it, cornerIcon);
            ui.post(() -> {
                for (ViewGroup g : new ViewGroup[] {strip, more}) {
                    for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).invalidate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- clock

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        // CleanFrame's clock: just the time, no AM/PM.
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", new Date()));
        if (meter.getVisibility() == View.VISIBLE) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        // hourly and time-of-day wallpaper rotations change while the screen stays open
        applyBackground();
    }

    // ---------------------------------------------------------------- opening things

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        if (Catalog.APPS.equals(t.kind)) {
            launch(new Intent(t.item.intent), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    // ---------------------------------------------------------------- tile menu

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(this, "App order is Recently used. Pick Your order "
                                        + "in Options to move tiles.", Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            if (app) renderApps();
                            else renderInputs();
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    /** The tiles of {@code kind} in screen order: for apps the dock row, then the grid. */
    private List<Tile> sequence(String kind) {
        List<Tile> out = new ArrayList<>();
        ViewGroup[] groups = Catalog.APPS.equals(kind) ? new ViewGroup[] {strip, more} : new ViewGroup[] {inputGrid};
        for (ViewGroup g : groups) {
            for (int i = 0; i < g.getChildCount(); i++) out.add((Tile) g.getChildAt(i));
        }
        return out;
    }

    /** Moves the tile being moved by {@code by} places, across the dock and the grid alike. */
    private void shift(int by) {
        String kind = moving.kind, id = moving.item.id;
        List<String> ids = new ArrayList<>();
        for (Tile t : sequence(kind)) ids.add(t.item.id);
        int from = ids.indexOf(id), to = from + by;
        if (from < 0 || to < 0 || to >= ids.size()) return;
        ids.remove(from);
        ids.add(to, id);
        Catalog.saveOrder(cfg, kind, ids);
        applied = cfg.version();
        if (Catalog.APPS.equals(kind)) renderApps();
        else renderInputs();
        for (Tile t : sequence(kind)) {
            if (t.item.id.equals(id)) {
                moving = t;
                t.setMoving(true);
                t.requestFocus();
            }
        }
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        List<String> ids = new ArrayList<>();
        for (Tile s : sequence(t.kind)) ids.add(s.item.id);
        if (!ids.isEmpty()) Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
