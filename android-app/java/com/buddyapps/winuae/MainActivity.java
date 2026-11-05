package com.buddyapps.winuae;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Laid out like WinUAE's own "Properties" window on Windows: a settings tree on the left
 * (Settings / Hardware / Host), the selected page on the right, and Reset / Quit / Start / Cancel
 * along the bottom. While the Amiga runs, the F12 button (WinUAE's GUI key) brings the window back.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PICK_ROM = 1, PICK_DISK = 2;
    private static final int WINDOW = 0xfff0f0f0, PANEL = 0xffffffff, BORDER = 0xffadadad, TEXT = 0xff000000,
            MUTED = 0xff6d6d6d, SELECT = 0xff0078d7, TITLE = 0xff1f1f1f;
    private static native void nativeSurface(Surface surface);
    private static native boolean nativeStart(String home, String rom, String disk);
    private static native void nativeStop();
    private static native void nativeMouse(int dx, int dy, boolean down);
    private static native void nativeKey(int code, boolean down);
    private static native void nativeJoystick(int control, boolean down);
    private static native void nativeWarp(boolean down);
    private static native boolean nativeState(String path, boolean load);
    private SurfaceView screen;
    private View properties;
    private FrameLayout page;
    private TextView statusLine;
    private Button startButton, cancelButton;
    private final java.util.List<TextView> treeItems = new java.util.ArrayList<>();
    private String romPath = "", diskPath = "", romName = "", diskName = "";
    private String currentPage = "Quickstart";
    private boolean started, fastForwardHeld, fullscreen = true, coreLoaded, havePointer;
    private float lastX, lastY;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(emulatorLayer(), new FrameLayout.LayoutParams(-1, -1));
        properties = propertiesWindow();
        root.addView(properties, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        File savedRom = new File(getFilesDir(), "kick.rom");
        File savedDisk = new File(getFilesDir(), "disk.adf");
        romName = getPreferences(MODE_PRIVATE).getString("rom_name", "kick.rom");
        diskName = getPreferences(MODE_PRIVATE).getString("disk_name", "disk.adf");
        if (savedRom.isFile()) romPath = savedRom.getAbsolutePath();
        if (savedDisk.isFile()) diskPath = savedDisk.getAbsolutePath();
        coreLoaded = loadCore();
        if (coreLoaded && screen.getHolder().getSurface().isValid()) nativeSurface(screen.getHolder().getSurface());
        showPage(currentPage);
        applyFullscreen();
    }

    // ------------------------------------------------------------------ emulator screen

    private View emulatorLayer() {
        LinearLayout layer = new LinearLayout(this);
        layer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout display = new FrameLayout(this);
        screen = new SurfaceView(this);
        screen.getHolder().addCallback(this);
        screen.setFocusable(true);
        screen.setOnTouchListener(this::touchScreen);
        display.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        Button f12 = flatButton("F12");
        f12.setContentDescription("Open WinUAE properties");
        f12.setOnClickListener(v -> showProperties(true));
        FrameLayout.LayoutParams f12Layout = new FrameLayout.LayoutParams(dp(56), dp(36), Gravity.TOP | Gravity.RIGHT);
        f12Layout.setMargins(0, dp(6), dp(6), 0);
        display.addView(f12, f12Layout);
        layer.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        keys.setBackgroundColor(WINDOW);
        keys.setPadding(dp(4), dp(2), dp(4), dp(2));
        keys.addView(keyButton("←", 80, 0)); keys.addView(keyButton("↓", 81, 1));
        keys.addView(keyButton("↑", 82, 2)); keys.addView(keyButton("→", 79, 3));
        keys.addView(keyButton("Fire", -1, 4));
        Button fastForward = flatButton("Warp");
        fastForward.setContentDescription("Hold for warp mode (fast forward)");
        fastForward.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) setFastForward(true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) setFastForward(false);
            return true;
        });
        keys.addView(fastForward, keyLayout());
        layer.addView(keys, new LinearLayout.LayoutParams(-1, -2));
        return layer;
    }

    // ------------------------------------------------------------------ properties window

    private View propertiesWindow() {
        LinearLayout window = new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setBackgroundColor(WINDOW);
        window.setClickable(true);

        TextView title = new TextView(this);
        title.setText("WinUAE Properties");
        title.setTextColor(TITLE);
        title.setTextSize(15);
        title.setBackgroundColor(PANEL);
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        window.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setPadding(dp(8), dp(8), dp(8), dp(4));
        ScrollView treeScroll = new ScrollView(this);
        LinearLayout tree = new LinearLayout(this);
        tree.setOrientation(LinearLayout.VERTICAL);
        tree.setBackground(box(PANEL));
        tree.setPadding(dp(2), dp(4), dp(2), dp(4));
        treeHeader(tree, "Settings");
        treeItem(tree, "About"); treeItem(tree, "Quickstart");
        treeHeader(tree, "Hardware");
        treeItem(tree, "ROM"); treeItem(tree, "Floppy drives");
        treeHeader(tree, "Host");
        treeItem(tree, "Display"); treeItem(tree, "Game ports"); treeItem(tree, "Miscellaneous");
        treeScroll.addView(tree);
        treeScroll.setBackground(box(PANEL));
        body.addView(treeScroll, new LinearLayout.LayoutParams(dp(170), -1));
        page = new FrameLayout(this);
        LinearLayout.LayoutParams pageLayout = new LinearLayout.LayoutParams(0, -1, 1);
        pageLayout.setMargins(dp(8), 0, 0, 0);
        body.addView(page, pageLayout);
        window.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(8), dp(2), dp(8), dp(6));
        Button reset = windowButton("Reset", v -> resetEmulator());
        Button quit = windowButton("Quit", v -> { stopEmulator(); finish(); });
        statusLine = new TextView(this);
        statusLine.setTextColor(MUTED);
        statusLine.setTextSize(12);
        statusLine.setPadding(dp(8), 0, dp(8), 0);
        startButton = windowButton("Start", v -> startOrResume());
        cancelButton = windowButton("Cancel", v -> showProperties(false));
        buttons.addView(reset); buttons.addView(quit);
        buttons.addView(statusLine, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(startButton); buttons.addView(cancelButton);
        window.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        return window;
    }

    private void treeHeader(LinearLayout tree, String text) {
        TextView t = new TextView(this);
        t.setText("▾ " + text);
        t.setTextColor(TEXT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(6), dp(6), dp(2));
        tree.addView(t);
    }

    private void treeItem(LinearLayout tree, String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setPadding(dp(24), dp(6), dp(8), dp(6));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> showPage(name));
        t.setTag(name);
        treeItems.add(t);
        tree.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showPage(String name) {
        currentPage = name;
        for (TextView t : treeItems) {
            boolean on = name.equals(t.getTag());
            t.setBackgroundColor(on ? SELECT : Color.TRANSPARENT);
            t.setTextColor(on ? Color.WHITE : TEXT);
        }
        page.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        switch (name) {
            case "About": about(content); break;
            case "ROM": romPage(content); break;
            case "Floppy drives": floppyPage(content); break;
            case "Display": displayPage(content); break;
            case "Game ports": portsPage(content); break;
            case "Miscellaneous": miscPage(content); break;
            default: quickstart(content);
        }
        scroll.addView(content);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        updateButtons();
    }

    private void quickstart(LinearLayout c) {
        LinearLayout hw = group(c, "Emulated Hardware");
        line(hw, "Model", "Amiga (defaults from the Kickstart ROM)");
        line(hw, "Kickstart ROM", romPath.isEmpty() ? "not selected" : romName);
        fileRow(hw, "Select Kickstart ROM…", v -> pick(PICK_ROM), null);
        LinearLayout fl = group(c, "Emulated Floppy Drives");
        line(fl, "DF0:", diskPath.isEmpty() ? "empty" : diskName);
        fileRow(fl, "Insert disk…", v -> pick(PICK_DISK), diskPath.isEmpty() ? null : v -> ejectDisk());
        if (!coreLoaded) note(c, "The WinUAE ARM64 core is missing from this APK, so the Amiga can't start.");
        else note(c, started ? "The Amiga is running. Changes to ROM or disk take effect after Reset." : "Choose a Kickstart ROM and a disk, then press Start.");
    }

    private void about(LinearLayout c) {
        LinearLayout g = group(c, "WinUAE");
        TextView name = new TextView(this);
        name.setText("WinUAE for Android");
        name.setTextColor(TEXT); name.setTextSize(20); name.setTypeface(Typeface.DEFAULT_BOLD);
        g.addView(name);
        plain(g, "An Android port of WinUAE, the Amiga emulator by Toni Wilen and contributors, based on UAE. Released under the GNU General Public License.");
        plain(g, "Kickstart ROMs and disk images are not included: use your own.");
    }

    private void romPage(LinearLayout c) {
        LinearLayout g = group(c, "System ROM Settings");
        line(g, "Main ROM file", romPath.isEmpty() ? "none" : romName);
        fileRow(g, "…", v -> pick(PICK_ROM), null);
        note(c, "Kickstart 1.3 (A500) or later. The ROM is copied into the app's private storage.");
    }

    private void floppyPage(LinearLayout c) {
        LinearLayout g = group(c, "Floppy drives");
        line(g, "DF0:", diskPath.isEmpty() ? "empty" : diskName);
        fileRow(g, "…", v -> pick(PICK_DISK), diskPath.isEmpty() ? null : v -> ejectDisk());
        for (String drive : new String[] { "DF1:", "DF2:", "DF3:" }) line(g, drive, "not available on Android yet");
        note(c, "ADF images. A disk change while the Amiga runs takes effect after Reset.");
    }

    private void displayPage(LinearLayout c) {
        LinearLayout g = group(c, "Screen");
        CheckBox full = new CheckBox(this);
        full.setText("Full screen (hide Android's status and navigation bars)");
        full.setTextColor(TEXT);
        full.setChecked(fullscreen);
        full.setOnCheckedChangeListener((b, on) -> { fullscreen = on; applyFullscreen(); });
        g.addView(full);
    }

    private void portsPage(LinearLayout c) {
        LinearLayout g = group(c, "Mouse and joystick");
        line(g, "Port 0", "Mouse: drag on the screen to move it, touch to click");
        line(g, "Port 1", "Joystick: on-screen arrows and Fire under the screen");
        note(c, "Hold Warp to run the emulation as fast as possible.");
    }

    private void miscPage(LinearLayout c) {
        LinearLayout g = group(c, "State files");
        LinearLayout row = new LinearLayout(this);
        row.addView(windowButton("Load State…", v -> loadState()));
        row.addView(windowButton("Save State…", v -> saveState()));
        g.addView(row);
        line(g, "Quick state", stateFile().isFile() ? "saved" : "none yet");
        note(c, started ? "Saving and loading apply to the running Amiga." : "Start the Amiga to save or load its state.");
    }

    private LinearLayout group(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(SELECT);
        t.setTextSize(13);
        t.setPadding(dp(4), dp(8), 0, dp(2));
        parent.addView(t);
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(box(WINDOW));
        g.setPadding(dp(10), dp(8), dp(10), dp(8));
        parent.addView(g, new LinearLayout.LayoutParams(-1, -2));
        return g;
    }

    private void line(LinearLayout g, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView l = new TextView(this);
        l.setText(label); l.setTextColor(TEXT); l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(dp(130), -2));
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(TEXT); v.setTextSize(14);
        v.setBackground(box(PANEL));
        v.setPadding(dp(6), dp(3), dp(6), dp(3));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        g.addView(row);
    }

    private void fileRow(LinearLayout g, String select, View.OnClickListener pickAction, View.OnClickListener eject) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.RIGHT);
        if (eject != null) row.addView(windowButton("Eject", eject));
        row.addView(windowButton(select, pickAction));
        g.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void note(LinearLayout c, String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(MUTED); t.setTextSize(13);
        t.setPadding(dp(4), dp(10), dp(4), 0);
        c.addView(t);
    }

    private void plain(LinearLayout g, String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(TEXT); t.setTextSize(14);
        t.setPadding(0, dp(6), 0, 0);
        g.addView(t);
    }

    private GradientDrawable box(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), BORDER);
        return d;
    }

    /** Windows-style push button: light face, grey border, blue border when focused. */
    private Button windowButton(String text, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setMinWidth(dp(88)); b.setMinimumWidth(dp(88));
        b.setMinHeight(dp(36)); b.setMinimumHeight(dp(36));
        b.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = box(0xffcce4f7); pressed.setStroke(dp(1), 0xff005499);
        GradientDrawable focused = box(0xffe1e1e1); focused.setStroke(dp(2), SELECT);
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[] { android.R.attr.state_focused }, focused);
        states.addState(new int[0], box(0xffe1e1e1));
        b.setBackground(states);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private Button flatButton(String text) {
        Button b = windowButton(text, null);
        b.setMinWidth(0); b.setMinimumWidth(0);
        return b;
    }

    private LinearLayout.LayoutParams keyLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(44));
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        return lp;
    }

    private void showProperties(boolean show) {
        if (show && started) setFastForward(false);
        properties.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) showPage(currentPage);
    }

    private void updateButtons() {
        if (startButton == null) return;
        startButton.setText(started ? "OK" : "Start");
        cancelButton.setVisibility(started ? View.VISIBLE : View.GONE);
        statusLine.setText(!coreLoaded ? "WinUAE core missing" : started ? "Amiga running" : romPath.isEmpty() ? "No Kickstart ROM" : "Ready");
    }

    private void applyFullscreen() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(fullscreen ? flags : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applyFullscreen();
    }

    @Override public void onBackPressed() {
        if (properties.getVisibility() == View.VISIBLE) {
            if (started) showProperties(false); else super.onBackPressed();
        } else showProperties(true);
    }

    // ------------------------------------------------------------------ emulation

    private boolean loadCore() {
        try {
            File dir = getDir("native", MODE_PRIVATE);
            int version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            File lib = new File(dir, "libwinuae-" + version + ".so");
            if (!lib.isFile() || lib.length() < 1024 * 1024) {
                File temporary = new File(dir, lib.getName() + ".tmp");
                temporary.delete();
                try (InputStream in = getAssets().open("libwinuae.so");
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (!temporary.renameTo(lib)) throw new java.io.IOException("Could not save native core");
            }
            lib.setReadable(true, false);
            lib.setWritable(false, false);
            System.load(lib.getAbsolutePath());
            return true;
        } catch (Exception | UnsatisfiedLinkError e) {
            Toast.makeText(this, "WinUAE ARM64 core missing: " + (e.getMessage() == null ? "load failed" : e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void setFastForward(boolean enabled) {
        if (!coreLoaded || !started || fastForwardHeld == enabled) return;
        fastForwardHeld = enabled;
        nativeWarp(enabled);
    }

    private File stateFile() { return new File(getFilesDir(), "quick.uss"); }

    private void saveState() {
        if (!coreLoaded || !started) Toast.makeText(this, "Start the Amiga before saving", Toast.LENGTH_SHORT).show();
        else if (nativeState(stateFile().getAbsolutePath(), false)) { Toast.makeText(this, "Saving state…", Toast.LENGTH_SHORT).show(); showProperties(false); }
        else Toast.makeText(this, "Could not queue save state", Toast.LENGTH_LONG).show();
    }

    private void loadState() {
        if (!coreLoaded || !started) Toast.makeText(this, "Start the Amiga before loading", Toast.LENGTH_SHORT).show();
        else if (!stateFile().isFile()) Toast.makeText(this, "No saved state yet", Toast.LENGTH_SHORT).show();
        else if (nativeState(stateFile().getAbsolutePath(), true)) { Toast.makeText(this, "Loading state…", Toast.LENGTH_SHORT).show(); showProperties(false); }
        else Toast.makeText(this, "Could not queue load state", Toast.LENGTH_LONG).show();
    }

    private Button keyButton(String title, int code, int control) {
        Button b = flatButton(title);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (coreLoaded && started) {
                    if (code >= 0) nativeKey(code, true);
                    else { nativeMouse(0, 0, true); nativeKey(228, true); }
                    nativeJoystick(control, true);
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (coreLoaded && started) {
                    if (code >= 0) nativeKey(code, false);
                    else { nativeMouse(0, 0, false); nativeKey(228, false); }
                    nativeJoystick(control, false);
                }
                return true;
            }
            return true;
        });
        b.setLayoutParams(keyLayout());
        return b;
    }

    private void pick(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        startActivityForResult(i, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        String name = request == PICK_ROM ? "kick.rom" : "disk.adf";
        try {
            File target = new File(getFilesDir(), name);
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            }
            String shown = displayName(data.getData(), name);
            if (request == PICK_ROM) { romPath = target.getAbsolutePath(); romName = shown; }
            else { diskPath = target.getAbsolutePath(); diskName = shown; }
            getPreferences(MODE_PRIVATE).edit().putString(request == PICK_ROM ? "rom_name" : "disk_name", shown).apply();
            showPage(currentPage);
        } catch (Exception e) { Toast.makeText(this, "Could not copy selected file", Toast.LENGTH_LONG).show(); }
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private void ejectDisk() {
        new File(getFilesDir(), "disk.adf").delete();
        diskPath = ""; diskName = "";
        showPage(currentPage);
    }

    private void startOrResume() {
        if (started) { showProperties(false); return; }
        if (!coreLoaded) { Toast.makeText(this, "WinUAE core is not included in this APK", Toast.LENGTH_LONG).show(); return; }
        if (romPath.isEmpty()) { showPage("ROM"); pick(PICK_ROM); return; }
        if (!nativeStart(getFilesDir().getAbsolutePath(), romPath, diskPath)) {
            Toast.makeText(this, "Native WinUAE core is unavailable", Toast.LENGTH_LONG).show(); return;
        }
        started = true;
        showProperties(false);
        updateButtons();
    }

    private void resetEmulator() {
        if (!started) { startOrResume(); return; }
        stopEmulator();
        startOrResume();
    }

    private void stopEmulator() {
        if (started) {
            setFastForward(false);
            nativeStop(); started = false;
            updateButtons();
        }
    }

    private boolean touchScreen(View view, MotionEvent event) {
        if (!coreLoaded || !started) return true;
        float x = event.getX(), y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) { lastX = x; lastY = y; havePointer = true; nativeMouse(0, 0, true); return true; }
        if (event.getAction() == MotionEvent.ACTION_MOVE && havePointer) {
            int dx = Math.round(x - lastX), dy = Math.round(y - lastY);
            nativeMouse(dx, dy, true); lastX = x; lastY = y; return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            havePointer = false; nativeMouse(0, 0, false); return true;
        }
        return true;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void surfaceCreated(SurfaceHolder holder) { if (coreLoaded) nativeSurface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { if (coreLoaded) nativeSurface(holder.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { if (coreLoaded) nativeSurface(null); }
    @Override protected void onDestroy() { stopEmulator(); if (coreLoaded) nativeSurface(null); super.onDestroy(); }
}
