package com.pixelstudio.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;
import com.pixelstudio.core.History;
import com.pixelstudio.core.LayerInfo;
import com.pixelstudio.core.Patch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** The drawing screen: tools, canvas, layers, timeline, playback, import/export and autosave. */
public class EditorActivity extends Activity implements CanvasView.Host, TimelineView.Listener {
    public static final String EXTRA_ID = "id";
    private static final int REQ_SAVE = 11, REQ_IMPORT = 12;
    private static final long AUTOSAVE_MS = 1500;

    private Doc doc;
    private History history;
    private final ToolState ts = new ToolState();
    private String projectId, projectName;

    private CanvasView canvas;
    private TimelineView timeline;
    private LinearLayout root, optionsBar, framesPanel, paletteHolder, toolsHolder, layersList;
    private TextView titleView, delayBtn, loopBtn;
    private IconView undoBtn, redoBtn, playBtn, onionBtn, gridBtn, previewBtn, framesBtn;
    private final IconView[] toolBtns = new IconView[ToolState.TOOL_COUNT];
    private SwatchView swatch;
    private boolean framesVisible = true;
    private boolean landscape;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dirty, playing;
    private int playsDone;
    private Dialog layersDialog;
    private Exporter.Options pendingExport;

    private final Runnable saveRunnable = new Runnable() {
        @Override public void run() { saveNow(); }
    };

    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            int next = doc.activeFrame + 1;
            if (next >= doc.frames.size()) {
                playsDone++;
                if (doc.loop != 0 && playsDone >= doc.loop) {
                    stopPlay();
                    return;
                }
                next = 0;
            }
            doc.activeFrame = next;
            canvas.refreshAll();
            timeline.invalidateCells();
            timeline.scrollToActive();
            handler.postDelayed(this, Math.max(20, doc.frame().delayMs));
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Settings.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(UI.BG);
        getWindow().setNavigationBarColor(UI.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        projectId = getIntent().getStringExtra(EXTRA_ID);
        if (projectId == null) {
            finish();
            return;
        }
        try {
            doc = ProjectStore.load(this, projectId);
        } catch (IOException | RuntimeException e) {
            UI.toast(this, R.string.open_failed);
            finish();
            return;
        }
        ProjectStore.Info info = ProjectStore.info(this, projectId);
        projectName = info != null ? info.name : getString(R.string.untitled);
        history = new History();
        initTools();

        canvas = new CanvasView(this);
        canvas.setHost(this);
        canvas.setDoc(doc, history, ts);
        timeline = new TimelineView(this);
        timeline.bind(doc, this);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        framesVisible = !landscape || doc.frames.size() > 1;
        buildUi();
        updateAll();
    }

    private void initTools() {
        boolean px = doc.pixelArt;
        ts.sizes[ToolState.PENCIL] = px ? 1 : 2;
        ts.sizes[ToolState.BRUSH] = px ? 2 : 8;
        ts.sizes[ToolState.ERASER] = px ? 1 : 10;
        ts.sizes[ToolState.LINE] = px ? 1 : 3;
        ts.sizes[ToolState.RECT] = px ? 1 : 3;
        ts.sizes[ToolState.ELLIPSE] = px ? 1 : 3;
        ts.color = Settings.getInt(this, "color", 0xFF000000);
        if ((ts.color >>> 24) == 0) ts.color = 0xFF000000;
        ts.grid = px && doc.w <= 128;
        ts.gridStep = 1;
        ts.tolerance = 0;
        ts.tool = ToolState.PENCIL;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (doc == null) return;
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land != landscape) {
            landscape = land;
            framesVisible = !land || doc.frames.size() > 1;
        }
        buildUi();
        updateAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (doc == null) return;
        stopPlay();
        canvas.commitFloating();
        saveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (doc != null) {
            stopPlay();
            canvas.commitFloating();
            saveNow();
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ saving

    private void markDirty() {
        dirty = true;
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, AUTOSAVE_MS);
    }

    private void saveNow() {
        handler.removeCallbacks(saveRunnable);
        if (!dirty || doc == null) return;
        try {
            ProjectStore.save(this, projectId, projectName, doc);
            dirty = false;
        } catch (IOException | RuntimeException e) {
            UI.toast(this, R.string.save_failed);
        }
    }

    // ------------------------------------------------------------------ UI construction

    private IconView tool(final int tool, int icon) {
        IconView v = UI.iconButton(this, icon, 44, new View.OnClickListener() {
            @Override public void onClick(View x) { selectTool(tool); }
        });
        toolBtns[tool] = v;
        return v;
    }

    private IconView action(int icon, View.OnClickListener l) {
        return UI.iconButton(this, icon, 44, l);
    }

    private void buildUi() {
        detach(canvas);
        detach(timeline);
        root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);

        // ---- tools
        toolsHolder = new LinearLayout(this);
        toolsHolder.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        toolsHolder.setGravity(Gravity.CENTER);
        int[][] defs = {
                {ToolState.PENCIL, IconView.PENCIL}, {ToolState.BRUSH, IconView.BRUSH},
                {ToolState.ERASER, IconView.ERASER}, {ToolState.BUCKET, IconView.BUCKET},
                {ToolState.LINE, IconView.LINE}, {ToolState.RECT, IconView.RECT},
                {ToolState.ELLIPSE, IconView.CIRCLE}, {ToolState.SELECT, IconView.SELECT},
                {ToolState.MOVE, IconView.MOVE}, {ToolState.HAND, IconView.HAND},
                {ToolState.PICKER, IconView.PICKER}};
        if (landscape) {
            swatch = new SwatchView(this);
            toolsHolder.addView(swatch, UI.lp(this, 36, 36));
        }
        for (int[] d : defs) toolsHolder.addView(tool(d[0], d[1]));
        toolsHolder.addView(UI.spacer(this, 8, 8));
        gridBtn = action(IconView.GRID, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ts.grid = !ts.grid;
                gridBtn.setActive(ts.grid);
                canvas.invalidate();
            }
        });
        toolsHolder.addView(gridBtn);
        toolsHolder.addView(action(IconView.ZOOM_IN, new View.OnClickListener() {
            @Override public void onClick(View v) { canvas.zoomBy(1.5f); }
        }));
        toolsHolder.addView(action(IconView.ZOOM_OUT, new View.OnClickListener() {
            @Override public void onClick(View v) { canvas.zoomBy(1f / 1.5f); }
        }));
        toolsHolder.addView(action(IconView.FIT, new View.OnClickListener() {
            @Override public void onClick(View v) { canvas.fit(); }
        }));
        previewBtn = action(IconView.PREVIEW, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ts.preview = !ts.preview;
                previewBtn.setActive(ts.preview);
                canvas.invalidate();
            }
        });
        toolsHolder.addView(previewBtn);
        framesBtn = action(IconView.FRAMES, new View.OnClickListener() {
            @Override public void onClick(View v) {
                framesVisible = !framesVisible;
                framesPanel.setVisibility(framesVisible ? View.VISIBLE : View.GONE);
                framesBtn.setActive(framesVisible);
                if (framesVisible) timeline.scrollToActive();
            }
        });
        toolsHolder.addView(framesBtn);

        View toolsScroll;
        if (landscape) {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(toolsHolder);
            sv.setBackgroundColor(UI.SURFACE);
            toolsScroll = sv;
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(toolsHolder);
            hs.setBackgroundColor(UI.SURFACE);
            toolsScroll = hs;
        }

        // ---- top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(UI.SURFACE);
        top.addView(action(IconView.BACK, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        }));
        titleView = UI.bold(this, projectName, 16, UI.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renameProject(); }
        });
        top.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        undoBtn = action(IconView.UNDO, new View.OnClickListener() {
            @Override public void onClick(View v) { undo(); }
        });
        redoBtn = action(IconView.REDO, new View.OnClickListener() {
            @Override public void onClick(View v) { redo(); }
        });
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(action(IconView.LAYERS, new View.OnClickListener() {
            @Override public void onClick(View v) { showLayers(); }
        }));
        top.addView(action(IconView.EXPORT, new View.OnClickListener() {
            @Override public void onClick(View v) { showExport(); }
        }));
        top.addView(action(IconView.MORE, new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        }));

        // ---- options, palette, frames
        optionsBar = new LinearLayout(this);
        optionsBar.setGravity(Gravity.CENTER_VERTICAL);
        optionsBar.setBackgroundColor(UI.SURFACE2);
        optionsBar.setPadding(UI.dp(this, 10), 0, UI.dp(this, 10), 0);
        HorizontalScrollView optScroll = new HorizontalScrollView(this);
        optScroll.setHorizontalScrollBarEnabled(false);
        optScroll.setFillViewport(true);
        optScroll.setBackgroundColor(UI.SURFACE2);
        optScroll.addView(optionsBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 48)));

        framesPanel = buildFramesPanel();
        framesPanel.setVisibility(framesVisible ? View.VISIBLE : View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(top, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));
        column.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(optScroll, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 48)));
        column.addView(framesPanel, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (landscape) {
            root.addView(toolsScroll, UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            // palette row: swatch + palette strip
            LinearLayout pal = new LinearLayout(this);
            pal.setGravity(Gravity.CENTER_VERTICAL);
            pal.setBackgroundColor(UI.SURFACE);
            pal.setPadding(UI.dp(this, 8), UI.dp(this, 4), UI.dp(this, 8), UI.dp(this, 4));
            swatch = new SwatchView(this);
            pal.addView(swatch, UI.lp(this, 40, 40));
            HorizontalScrollView ps = new HorizontalScrollView(this);
            ps.setHorizontalScrollBarEnabled(false);
            paletteHolder = new LinearLayout(this);
            paletteHolder.setGravity(Gravity.CENTER_VERTICAL);
            ps.addView(paletteHolder);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pl.leftMargin = UI.dp(this, 8);
            pal.addView(ps, pl);
            column.addView(pal, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 48)));
            column.addView(toolsScroll, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));
            root.addView(column, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
        root.setFitsSystemWindows(true);
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private LinearLayout buildFramesPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(UI.SURFACE);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0);
        playBtn = UI.iconButton(this, IconView.PLAY, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { if (playing) stopPlay(); else startPlay(); }
        });
        row.addView(playBtn);
        row.addView(UI.iconButton(this, IconView.LEFT, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { stepFrame(-1); }
        }));
        row.addView(UI.iconButton(this, IconView.RIGHT, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { stepFrame(1); }
        }));
        onionBtn = UI.iconButton(this, IconView.ONION, 40, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ts.onion = !ts.onion;
                onionBtn.setActive(ts.onion);
                canvas.updateOnion();
                canvas.invalidate();
            }
        });
        row.addView(onionBtn);
        row.addView(UI.iconButton(this, IconView.COPY, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { duplicateFrame(); }
        }));
        row.addView(UI.iconButton(this, IconView.TRASH, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteFrame(); }
        }));
        delayBtn = UI.button(this, "100 ms", UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { askDelay(doc.activeFrame, false); }
        });
        delayBtn.setTextSize(12);
        LinearLayout.LayoutParams dl = UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 34));
        dl.leftMargin = UI.dp(this, 6);
        row.addView(delayBtn, dl);
        loopBtn = UI.button(this, "", UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { askLoop(); }
        });
        loopBtn.setTextSize(12);
        LinearLayout.LayoutParams ll = UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 34));
        ll.leftMargin = UI.dp(this, 6);
        row.addView(loopBtn, ll);
        hs.addView(row);
        p.addView(hs, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44)));
        p.addView(timeline, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 80)));
        return p;
    }

    // ------------------------------------------------------------------ state sync

    private void updateAll() {
        for (int i = 0; i < toolBtns.length; i++) if (toolBtns[i] != null) toolBtns[i].setActive(i == ts.tool);
        gridBtn.setActive(ts.grid);
        previewBtn.setActive(ts.preview);
        framesBtn.setActive(framesVisible);
        onionBtn.setActive(ts.onion);
        playBtn.setIcon(playing ? IconView.PAUSE : IconView.PLAY);
        updateOptions();
        updateHistoryButtons();
        updateFrameLabels();
        buildPalette();
        if (swatch != null) swatch.invalidate();
        titleView.setText(projectName);
        timeline.refresh();
        canvas.refreshAll();
    }

    private void updateHistoryButtons() {
        if (undoBtn == null) return;
        undoBtn.setEnabled(history.canUndo());
        redoBtn.setEnabled(history.canRedo());
    }

    private void updateFrameLabels() {
        if (delayBtn == null) return;
        delayBtn.setText(doc.frame().delayMs + " ms");
        loopBtn.setText(getString(R.string.loop) + ": " + (doc.loop == 0 ? "∞" : String.valueOf(doc.loop)));
    }

    private void buildPalette() {
        if (paletteHolder == null) return;
        paletteHolder.removeAllViews();
        List<Integer> recent = Settings.recentColors(this);
        int count = 0;
        for (int c : recent) {
            if (count++ >= 8) break;
            addPaletteSwatch(c);
        }
        int[] pal = Palettes.get(1);
        for (int c : pal) addPaletteSwatch(c);
    }

    private void addPaletteSwatch(final int c) {
        View v = new View(this);
        v.setBackground(UI.roundedStroke(c, 0x66FFFFFF, 1, 6, this));
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { setColor(c); }
        });
        LinearLayout.LayoutParams lp = UI.lp(this, 34, 34);
        lp.rightMargin = UI.dp(this, 5);
        paletteHolder.addView(v, lp);
    }

    private void setColor(int c) {
        ts.color = c;
        if ((c >>> 24) != 0) Settings.putInt(this, "color", c);
        if (swatch != null) swatch.invalidate();
        if (ts.tool == ToolState.PICKER) return;
    }

    private void selectTool(int t) {
        if (t != ToolState.SELECT && t != ToolState.MOVE) canvas.commitFloating();
        ts.tool = t;
        for (int i = 0; i < toolBtns.length; i++) if (toolBtns[i] != null) toolBtns[i].setActive(i == t);
        updateOptions();
        canvas.invalidate();
    }

    // ------------------------------------------------------------------ options bar

    private TextView hint(int res) {
        TextView t = UI.label(this, getString(res), 13, UI.TEXT2);
        return t;
    }

    private void addOpt(View v) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(this, 8);
        optionsBar.addView(v, lp);
    }

    private SeekBar slider(int max, int progress, SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setProgressTintList(ColorStateList.valueOf(UI.ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(UI.ACCENT));
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    private void updateOptions() {
        if (optionsBar == null) return;
        optionsBar.removeAllViews();
        int t = ts.tool;
        if (ts.usesSize()) {
            final TextView lbl = UI.bold(this, getString(R.string.size) + " " + ts.size(), 13, UI.TEXT);
            lbl.setMinWidth(UI.dp(this, 64));
            addOpt(lbl);
            int max = doc.pixelArt ? Math.max(2, Math.min(64, Math.max(doc.w, doc.h) / 2)) : 128;
            final int tool = t;
            SeekBar sb = slider(max - 1, Math.min(max, ts.size()) - 1, new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    ts.sizes[tool] = p + 1;
                    lbl.setText(getString(R.string.size) + " " + (p + 1));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            addOpt(sb);
            sb.getLayoutParams().width = UI.dp(this, 190);
            if (ToolState.isShapeTool(t)) {
                final TextView fill = UI.button(this, getString(ts.fillShape ? R.string.filled : R.string.outline),
                        UI.SURFACE3, UI.TEXT, null);
                fill.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ts.fillShape = !ts.fillShape;
                        fill.setText(ts.fillShape ? R.string.filled : R.string.outline);
                    }
                });
                addOpt(fill);
            }
            if (t == ToolState.PENCIL && doc.pixelArt) addOpt(hint(R.string.hint_pixel_pencil));
        } else if (t == ToolState.BUCKET) {
            final TextView lbl = UI.bold(this, getString(R.string.tolerance) + " " + ts.tolerance, 13, UI.TEXT);
            lbl.setMinWidth(UI.dp(this, 90));
            addOpt(lbl);
            SeekBar sb = slider(128, ts.tolerance, new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    ts.tolerance = p;
                    lbl.setText(getString(R.string.tolerance) + " " + p);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            addOpt(sb);
            sb.getLayoutParams().width = UI.dp(this, 190);
        } else if (t == ToolState.SELECT || t == ToolState.MOVE) {
            if (canvas.isFloating()) {
                addOpt(UI.button(this, getString(R.string.place), UI.ACCENT, Color.WHITE, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        canvas.commitFloating();
                        updateOptions();
                    }
                }));
            }
            addOpt(optIcon(IconView.SELECT_ALL, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.selectAll(); updateOptions(); }
            }));
            addOpt(optIcon(IconView.CUT, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.cutSelection(); }
            }));
            addOpt(optIcon(IconView.COPY, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.copySelection(); }
            }));
            addOpt(optIcon(IconView.PASTE, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.pasteClipboard(); updateOptions(); }
            }));
            addOpt(optIcon(IconView.TRASH, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.deleteSelection(); }
            }));
            addOpt(optIcon(IconView.CLOSE, new View.OnClickListener() {
                @Override public void onClick(View v) { canvas.deselect(); updateOptions(); }
            }));
            addOpt(optIcon(IconView.IMAGE, new View.OnClickListener() {
                @Override public void onClick(View v) { pickImage(); }
            }));
            addOpt(hint(t == ToolState.SELECT ? R.string.hint_select : R.string.hint_move));
        } else if (t == ToolState.HAND) {
            addOpt(hint(R.string.hint_hand));
        } else if (t == ToolState.PICKER) {
            addOpt(hint(R.string.hint_picker));
        }
    }

    private IconView optIcon(int icon, View.OnClickListener l) {
        IconView v = UI.iconButton(this, icon, 38, l);
        v.setBackground(UI.rounded(UI.SURFACE3, 10, this));
        return v;
    }

    // ------------------------------------------------------------------ Host callbacks

    @Override public void onEdited() {
        markDirty();
        timeline.invalidateCells();
    }

    @Override public void onColorPicked(int argb) {
        if ((argb >>> 24) == 0) {
            UI.toast(this, R.string.picked_transparent);
            return;
        }
        Settings.addRecentColor(this, argb);
        setColor(argb);
        buildPalette();
    }

    @Override public void onMessage(int resId) { UI.toast(this, resId); }
    @Override public void onHistoryChanged() { updateHistoryButtons(); }
    @Override public void onRequestUndo() { undo(); }
    @Override public void onRequestRedo() { redo(); }
    @Override public void onRequestTool(int tool) {
        selectTool(tool);
    }

    // ------------------------------------------------------------------ history

    private void afterStructChange() {
        doc.clampActive();
        canvas.refreshAll();
        timeline.refresh();
        updateHistoryButtons();
        updateFrameLabels();
        if (layersDialog != null && layersDialog.isShowing()) rebuildLayers();
        markDirty();
    }

    private void structOp(Runnable op) {
        stopPlay();
        canvas.commitFloating();
        history.struct(doc, op);
        afterStructChange();
    }

    private void undo() {
        stopPlay();
        canvas.commitFloating();
        if (history.undo(doc)) {
            canvas.deselect();
            afterStructChange();
        }
    }

    private void redo() {
        stopPlay();
        canvas.commitFloating();
        if (history.redo(doc)) {
            canvas.deselect();
            afterStructChange();
        }
    }

    // ------------------------------------------------------------------ frames

    @Override public void onSelect(int index) {
        stopPlay();
        setFrame(index);
    }

    private void setFrame(int index) {
        canvas.commitFloating();
        doc.activeFrame = Math.max(0, Math.min(doc.frames.size() - 1, index));
        canvas.refreshAll();
        timeline.invalidateCells();
        timeline.scrollToActive();
        updateFrameLabels();
    }

    private void stepFrame(int d) {
        stopPlay();
        int n = doc.frames.size();
        setFrame(((doc.activeFrame + d) % n + n) % n);
    }

    @Override public void onAdd() {
        structOp(new Runnable() {
            @Override public void run() { doc.addFrame(doc.frames.size(), -1); }
        });
    }

    private void duplicateFrame() {
        final int i = doc.activeFrame;
        structOp(new Runnable() {
            @Override public void run() { doc.addFrame(i + 1, i); }
        });
    }

    private void deleteFrame() {
        if (doc.frames.size() <= 1) {
            UI.toast(this, R.string.cant_delete_last_frame);
            return;
        }
        final int i = doc.activeFrame;
        structOp(new Runnable() {
            @Override public void run() { doc.removeFrame(i); }
        });
    }

    @Override public void onMenu(final int index, View anchor) {
        stopPlay();
        setFrame(index);
        showList(getString(R.string.frame) + " " + (index + 1), new String[]{
                getString(R.string.duplicate), getString(R.string.delete), getString(R.string.move_earlier),
                getString(R.string.move_later), getString(R.string.frame_duration),
                getString(R.string.duration_all), getString(R.string.clear_frame)
        }, new Runnable[]{
                new Runnable() { @Override public void run() { duplicateFrame(); } },
                new Runnable() { @Override public void run() { deleteFrame(); } },
                new Runnable() { @Override public void run() {
                    if (index > 0) structOp(new Runnable() { @Override public void run() { doc.moveFrame(index, index - 1); } });
                } },
                new Runnable() { @Override public void run() {
                    if (index < doc.frames.size() - 1) structOp(new Runnable() { @Override public void run() { doc.moveFrame(index, index + 1); } });
                } },
                new Runnable() { @Override public void run() { askDelay(index, false); } },
                new Runnable() { @Override public void run() { askDelay(index, true); } },
                new Runnable() { @Override public void run() { clearLayerPixels(index, -1); } }
        });
    }

    private void askDelay(final int frame, final boolean all) {
        UI.promptNumber(this, getString(all ? R.string.duration_all : R.string.frame_duration) + " (ms)",
                doc.frames.get(frame).delayMs, 20, 10000, getString(R.string.ok), getString(R.string.cancel), new UI.IntResult() {
                    @Override public void onInt(final int v) {
                        structOp(new Runnable() {
                            @Override public void run() {
                                if (all) for (Frame f : doc.frames) f.delayMs = v;
                                else doc.frames.get(frame).delayMs = v;
                            }
                        });
                    }
                });
    }

    private void askLoop() {
        UI.promptNumber(this, getString(R.string.loop_count_title), doc.loop, 0, 999,
                getString(R.string.ok), getString(R.string.cancel), new UI.IntResult() {
                    @Override public void onInt(final int v) {
                        structOp(new Runnable() {
                            @Override public void run() { doc.loop = v; }
                        });
                    }
                });
    }

    private void startPlay() {
        if (doc.frames.size() < 2) {
            UI.toast(this, R.string.need_two_frames);
            return;
        }
        canvas.commitFloating();
        playing = true;
        playsDone = 0;
        canvas.setPlaying(true);
        playBtn.setIcon(IconView.PAUSE);
        handler.removeCallbacks(playTick);
        handler.postDelayed(playTick, Math.max(20, doc.frame().delayMs));
    }

    private void stopPlay() {
        if (!playing) return;
        playing = false;
        handler.removeCallbacks(playTick);
        canvas.setPlaying(false);
        if (playBtn != null) playBtn.setIcon(IconView.PLAY);
        canvas.refreshAll();
        timeline.invalidateCells();
        updateFrameLabels();
    }

    // ------------------------------------------------------------------ layers

    private void showLayers() {
        stopPlay();
        canvas.commitFloating();
        layersList = new LinearLayout(this);
        layersList.setOrientation(LinearLayout.VERTICAL);
        layersDialog = UI.dialog(this, getString(R.string.layers), layersList, getString(R.string.close), null, null, null);
        rebuildLayers();
        layersDialog.show();
    }

    private void rebuildLayers() {
        layersList.removeAllViews();
        for (int i = doc.layers.size() - 1; i >= 0; i--) {
            final int idx = i;
            LayerInfo li = doc.layers.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            boolean active = i == doc.activeLayer;
            row.setBackground(UI.roundedStroke(active ? UI.SURFACE3 : UI.SURFACE2, active ? UI.ACCENT : UI.SURFACE3, 1.5f, 10, this));
            row.setPadding(UI.dp(this, 4), UI.dp(this, 2), UI.dp(this, 8), UI.dp(this, 2));
            IconView eye = UI.iconButton(this, li.visible ? IconView.EYE : IconView.EYE_OFF, 40, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    structOp(new Runnable() {
                        @Override public void run() { doc.layers.get(idx).visible = !doc.layers.get(idx).visible; }
                    });
                }
            });
            row.addView(eye);
            TextView name = UI.label(this, li.name + (li.opacity < 255 ? "  (" + Math.round(li.opacity * 100f / 255f) + "%)" : ""), 15, UI.TEXT);
            name.setSingleLine(true);
            name.setPadding(UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    canvas.commitFloating();
                    doc.activeLayer = idx;
                    rebuildLayers();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    renameLayer(idx);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UI.dp(this, 6);
            layersList.addView(row, lp);
        }
        // opacity
        final LayerInfo act = doc.layers.get(doc.activeLayer);
        final TextView ol = UI.label(this, getString(R.string.opacity) + " " + Math.round(act.opacity * 100f / 255f) + "%", 13, UI.TEXT2);
        layersList.addView(ol);
        final Doc.Snapshot[] before = new Doc.Snapshot[1];
        SeekBar sb = slider(100, Math.round(act.opacity * 100f / 255f), new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                act.opacity = Math.round(p * 255f / 100f);
                ol.setText(getString(R.string.opacity) + " " + p + "%");
                canvas.refreshAll();
                timeline.invalidateCells();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                before[0] = doc.snapshot();
                // snapshot() copies layer info; the live value is the same at this point
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (before[0] == null) return;
                for (Frame f : doc.frames) f.version++;
                history.structManual(before[0], doc.snapshot());
                before[0] = null;
                updateHistoryButtons();
                markDirty();
                rebuildLayers();
            }
        });
        layersList.addView(sb);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.addView(layerBtn(IconView.ADD, new Runnable() { @Override public void run() {
            final int at = doc.activeLayer + 1;
            structOp(new Runnable() { @Override public void run() {
                doc.addLayer(at, getString(R.string.layer) + " " + (doc.layers.size() + 1));
            } });
        } }));
        tools.addView(layerBtn(IconView.COPY, new Runnable() { @Override public void run() {
            final int at = doc.activeLayer;
            structOp(new Runnable() { @Override public void run() { doc.duplicateLayer(at); } });
        } }));
        tools.addView(layerBtn(IconView.UP, new Runnable() { @Override public void run() {
            final int at = doc.activeLayer;
            if (at < doc.layers.size() - 1) structOp(new Runnable() { @Override public void run() { doc.moveLayer(at, at + 1); } });
        } }));
        tools.addView(layerBtn(IconView.DOWN, new Runnable() { @Override public void run() {
            final int at = doc.activeLayer;
            if (at > 0) structOp(new Runnable() { @Override public void run() { doc.moveLayer(at, at - 1); } });
        } }));
        tools.addView(layerBtn(IconView.MERGE, new Runnable() { @Override public void run() {
            final int at = doc.activeLayer;
            if (at > 0) structOp(new Runnable() { @Override public void run() { doc.mergeDown(at); } });
            else UI.toast(EditorActivity.this, R.string.cant_merge_bottom);
        } }));
        tools.addView(layerBtn(IconView.TRASH, new Runnable() { @Override public void run() {
            if (doc.layers.size() <= 1) {
                UI.toast(EditorActivity.this, R.string.cant_delete_last_layer);
                return;
            }
            final int at = doc.activeLayer;
            structOp(new Runnable() { @Override public void run() { doc.removeLayer(at); } });
        } }));
        LinearLayout.LayoutParams tl = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = UI.dp(this, 8);
        layersList.addView(tools, tl);
    }

    private IconView layerBtn(int icon, final Runnable r) {
        IconView v = UI.iconButton(this, icon, 44, new View.OnClickListener() {
            @Override public void onClick(View x) { r.run(); }
        });
        v.setBackground(UI.rounded(UI.SURFACE3, 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 44));
        lp.setMargins(UI.dp(this, 3), 0, UI.dp(this, 3), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void renameLayer(final int idx) {
        UI.promptText(this, getString(R.string.rename), doc.layers.get(idx).name, getString(R.string.ok),
                getString(R.string.cancel), new UI.TextResult() {
                    @Override public void onText(final String s) {
                        if (s.isEmpty()) return;
                        structOp(new Runnable() {
                            @Override public void run() { doc.layers.get(idx).name = s; }
                        });
                    }
                });
    }

    // ------------------------------------------------------------------ layer pixel ops

    /** Clears one layer of one frame (layer -1 = active layer) as a single undo step. */
    private void clearLayerPixels(int frame, int layer) {
        stopPlay();
        canvas.commitFloating();
        if (layer < 0) layer = doc.activeLayer;
        int[] px = doc.frames.get(frame).px[layer];
        int[] before = px.clone();
        java.util.Arrays.fill(px, 0);
        Patch p = Patch.fromDiff(before, px, doc.w, 0, 0, doc.w, doc.h);
        if (p == null) return;
        history.pushPatch(doc, frame, layer, p);
        canvas.refreshAll();
        timeline.invalidateCells();
        updateHistoryButtons();
        markDirty();
    }

    private void flipLayer(boolean horizontal) {
        stopPlay();
        canvas.commitFloating();
        int w = doc.w, h = doc.h;
        int[] px = doc.layerPixels();
        int[] before = px.clone();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = horizontal ? w - 1 - x : x;
                int sy = horizontal ? y : h - 1 - y;
                px[y * w + x] = before[sy * w + sx];
            }
        }
        Patch p = Patch.fromDiff(before, px, w, 0, 0, w, h);
        if (p == null) return;
        history.pushPatch(doc, doc.activeFrame, doc.activeLayer, p);
        canvas.refreshAll();
        timeline.invalidateCells();
        updateHistoryButtons();
        markDirty();
    }

    // ------------------------------------------------------------------ menus & dialogs

    private void showList(CharSequence title, String[] labels, final Runnable[] actions) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dlg = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = UI.label(this, labels[i], 15, UI.TEXT);
            t.setPadding(UI.dp(this, 12), UI.dp(this, 13), UI.dp(this, 12), UI.dp(this, 13));
            t.setBackground(UI.rounded(UI.SURFACE2, 10, this));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg[0].dismiss();
                    actions[idx].run();
                }
            });
            LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UI.dp(this, 6);
            box.addView(t, lp);
        }
        dlg[0] = UI.dialog(this, title, box, null, null, getString(R.string.close), null);
        dlg[0].show();
    }

    private void showMenu() {
        stopPlay();
        showList(getString(R.string.menu), new String[]{
                getString(R.string.rename),
                getString(R.string.import_image),
                getString(R.string.flip_h),
                getString(R.string.flip_v),
                getString(R.string.clear_layer),
                getString(R.string.grid_size),
                getString(R.string.canvas_background),
                getString(R.string.language),
                getString(R.string.help_shortcuts)
        }, new Runnable[]{
                new Runnable() { @Override public void run() { renameProject(); } },
                new Runnable() { @Override public void run() { pickImage(); } },
                new Runnable() { @Override public void run() { flipLayer(true); } },
                new Runnable() { @Override public void run() { flipLayer(false); } },
                new Runnable() { @Override public void run() { clearLayerPixels(doc.activeFrame, -1); } },
                new Runnable() { @Override public void run() { askGridSize(); } },
                new Runnable() { @Override public void run() { askBackground(); } },
                new Runnable() { @Override public void run() { toggleLanguage(); } },
                new Runnable() { @Override public void run() { showHelp(); } }
        });
    }

    private void renameProject() {
        UI.promptText(this, getString(R.string.rename), projectName, getString(R.string.ok), getString(R.string.cancel),
                new UI.TextResult() {
                    @Override public void onText(String s) {
                        if (s.isEmpty()) return;
                        projectName = s;
                        titleView.setText(s);
                        dirty = true;
                        saveNow();
                    }
                });
    }

    private void askGridSize() {
        final int[] steps = {1, 2, 4, 8, 16, 32};
        String[] labels = new String[steps.length];
        Runnable[] acts = new Runnable[steps.length];
        for (int i = 0; i < steps.length; i++) {
            final int s = steps[i];
            labels[i] = s + " × " + s;
            acts[i] = new Runnable() {
                @Override public void run() {
                    ts.gridStep = s;
                    ts.grid = true;
                    gridBtn.setActive(true);
                    canvas.invalidate();
                }
            };
        }
        showList(getString(R.string.grid_size), labels, acts);
    }

    private void askBackground() {
        showList(getString(R.string.canvas_background), new String[]{
                getString(R.string.transparent), getString(R.string.white), getString(R.string.black), getString(R.string.custom)
        }, new Runnable[]{
                new Runnable() { @Override public void run() { setDocBg(0); } },
                new Runnable() { @Override public void run() { setDocBg(0xFFFFFFFF); } },
                new Runnable() { @Override public void run() { setDocBg(0xFF000000); } },
                new Runnable() { @Override public void run() {
                    ColorDialog.show(EditorActivity.this, doc.bg == 0 ? 0xFFFFFFFF : doc.bg, new ColorDialog.Result() {
                        @Override public void onColor(int c) { setDocBg(c); }
                    });
                } }
        });
    }

    private void setDocBg(final int c) {
        structOp(new Runnable() {
            @Override public void run() { doc.bg = c; }
        });
        UI.toast(this, R.string.bg_applied);
    }

    private void toggleLanguage() {
        canvas.commitFloating();
        dirty = true;
        saveNow();
        Settings.setLanguage(this, Settings.isArabic(this) ? "en" : "ar");
        recreate();
    }

    private void showHelp() {
        TextView t = UI.label(this, getString(R.string.help_text), 14, UI.TEXT);
        t.setLineSpacing(0, 1.2f);
        UI.dialog(this, getString(R.string.help_shortcuts), t, getString(R.string.close), null, null, null).show();
    }

    // ------------------------------------------------------------------ keyboard shortcuts

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (doc == null) return super.onKeyDown(keyCode, e);
        if (e.isCtrlPressed()) {
            if (keyCode == KeyEvent.KEYCODE_Z) {
                if (e.isShiftPressed()) redo(); else undo();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_Y) { redo(); return true; }
            if (keyCode == KeyEvent.KEYCODE_C) { canvas.copySelection(); return true; }
            if (keyCode == KeyEvent.KEYCODE_X) { canvas.cutSelection(); return true; }
            if (keyCode == KeyEvent.KEYCODE_V) { canvas.pasteClipboard(); updateOptions(); return true; }
            if (keyCode == KeyEvent.KEYCODE_A) { canvas.selectAll(); selectTool(ToolState.SELECT); return true; }
            if (keyCode == KeyEvent.KEYCODE_S) { dirty = true; saveNow(); return true; }
            return super.onKeyDown(keyCode, e);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_P: selectTool(ToolState.PENCIL); return true;
            case KeyEvent.KEYCODE_B: selectTool(ToolState.BRUSH); return true;
            case KeyEvent.KEYCODE_E: selectTool(ToolState.ERASER); return true;
            case KeyEvent.KEYCODE_G: selectTool(ToolState.BUCKET); return true;
            case KeyEvent.KEYCODE_L: selectTool(ToolState.LINE); return true;
            case KeyEvent.KEYCODE_R: selectTool(ToolState.RECT); return true;
            case KeyEvent.KEYCODE_O: selectTool(ToolState.ELLIPSE); return true;
            case KeyEvent.KEYCODE_S: selectTool(ToolState.SELECT); return true;
            case KeyEvent.KEYCODE_M: selectTool(ToolState.MOVE); return true;
            case KeyEvent.KEYCODE_H: selectTool(ToolState.HAND); return true;
            case KeyEvent.KEYCODE_I: selectTool(ToolState.PICKER); return true;
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS: canvas.zoomBy(1.5f); return true;
            case KeyEvent.KEYCODE_MINUS: canvas.zoomBy(1f / 1.5f); return true;
            case KeyEvent.KEYCODE_0: canvas.fit(); return true;
            case KeyEvent.KEYCODE_SPACE:
                if (playing) stopPlay(); else startPlay();
                return true;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                ts.sizes[ts.tool] = Math.max(1, ts.sizes[ts.tool] - 1);
                updateOptions();
                return true;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                ts.sizes[ts.tool] = Math.min(128, ts.sizes[ts.tool] + 1);
                updateOptions();
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                canvas.deleteSelection();
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    // ------------------------------------------------------------------ import image

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            UI.toast(this, R.string.no_app_found);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null || doc == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final ImportUtil.Img img = ImportUtil.imageForPaste(EditorActivity.this, uri, doc.w, doc.h);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                canvas.pasteImage(img.px, img.w, img.h);
                                updateOptions();
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { UI.toast(EditorActivity.this, R.string.import_failed); }
                        });
                    }
                }
            }).start();
        } else if (req == REQ_SAVE && pendingExport != null) {
            final Exporter.Options o = pendingExport;
            pendingExport = null;
            runExport(o, uri, null);
        }
    }

    // ------------------------------------------------------------------ export

    private int exportBg = 0;

    private void showExport() {
        stopPlay();
        canvas.commitFloating();
        final Exporter.Options o = new Exporter.Options();
        o.format = doc.frames.size() > 1 ? Exporter.GIF : Exporter.PNG;
        o.frame = doc.activeFrame;
        o.background = doc.bg;
        o.scale = doc.pixelArt ? Math.max(1, Math.min(Exporter.maxScale(doc), 512 / Math.max(doc.w, doc.h))) : 1;
        final int maxScale = Exporter.maxScale(doc);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];

        // format chips
        final String[] fmtNames = {"PNG", "JPG", "GIF", getString(R.string.project)};
        final int[] fmtIds = {Exporter.PNG, Exporter.JPG, Exporter.GIF, Exporter.PROJECT};
        final TextView[] fmtViews = new TextView[4];
        LinearLayout fmtRow = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            final int fid = fmtIds[i];
            TextView t = UI.button(this, fmtNames[i], UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    o.format = fid;
                    render[0].run();
                }
            });
            fmtViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = UI.dp(this, 6);
            fmtRow.addView(t, lp);
        }
        box.addView(fmtRow);

        final TextView scaleLabel = UI.bold(this, "", 14, UI.TEXT);
        final TextView info = UI.label(this, "", 12, UI.TEXT2);
        final LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        scaleRow.addView(UI.button(this, "−", UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { o.scale = Math.max(1, o.scale - (o.scale > 8 ? 2 : 1)); render[0].run(); }
        }));
        scaleLabel.setGravity(Gravity.CENTER);
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scaleRow.addView(UI.button(this, "+", UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { o.scale = Math.min(maxScale, o.scale + (o.scale >= 8 ? 2 : 1)); render[0].run(); }
        }));

        final LinearLayout bgRow = new LinearLayout(this);
        final TextView bgTransparent = UI.button(this, getString(R.string.transparent), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { o.background = 0; render[0].run(); }
        });
        final TextView bgWhite = UI.button(this, getString(R.string.white), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { o.background = 0xFFFFFFFF; render[0].run(); }
        });
        final TextView bgBlack = UI.button(this, getString(R.string.black), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { o.background = 0xFF000000; render[0].run(); }
        });
        final TextView bgCustom = UI.button(this, getString(R.string.custom), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ColorDialog.show(EditorActivity.this, o.background == 0 ? 0xFFFFFFFF : o.background, new ColorDialog.Result() {
                    @Override public void onColor(int c) { o.background = 0xFF000000 | c; render[0].run(); }
                });
            }
        });
        TextView[] bgs = {bgTransparent, bgWhite, bgBlack, bgCustom};
        for (TextView t : bgs) {
            t.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = UI.dp(this, 6);
            bgRow.addView(t, lp);
        }

        final TextView qLabel = UI.label(this, "", 13, UI.TEXT2);
        final SeekBar qBar = slider(99, o.jpgQuality - 1, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                o.jpgQuality = p + 1;
                qLabel.setText(getString(R.string.quality) + " " + (p + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        qBar.setProgress(o.jpgQuality - 1);

        box.addView(sectionLabel(R.string.resolution));
        box.addView(scaleRow);
        box.addView(info);
        box.addView(sectionLabel(R.string.background));
        box.addView(bgRow);
        box.addView(qLabel);
        box.addView(qBar);

        render[0] = new Runnable() {
            @Override public void run() {
                if (o.format == Exporter.JPG && o.background == 0) o.background = 0xFFFFFFFF;
                for (int i = 0; i < 4; i++) {
                    boolean on = fmtIds[i] == o.format;
                    fmtViews[i].setBackground(UI.rounded(on ? UI.ACCENT : UI.SURFACE2, 10, EditorActivity.this));
                    fmtViews[i].setTextColor(on ? Color.WHITE : UI.TEXT);
                }
                boolean project = o.format == Exporter.PROJECT;
                scaleRow.setVisibility(project ? View.GONE : View.VISIBLE);
                bgRow.setVisibility(project ? View.GONE : View.VISIBLE);
                bgTransparent.setEnabled(o.format != Exporter.JPG);
                int bg = o.background;
                setChip(bgTransparent, bg == 0);
                setChip(bgWhite, bg == 0xFFFFFFFF);
                setChip(bgBlack, bg == 0xFF000000);
                setChip(bgCustom, bg != 0 && bg != 0xFFFFFFFF && bg != 0xFF000000);
                o.scale = Math.max(1, Math.min(maxScale, o.scale));
                scaleLabel.setText("×" + o.scale + "   " + (doc.w * o.scale) + " × " + (doc.h * o.scale));
                qLabel.setVisibility(o.format == Exporter.JPG ? View.VISIBLE : View.GONE);
                qBar.setVisibility(o.format == Exporter.JPG ? View.VISIBLE : View.GONE);
                qLabel.setText(getString(R.string.quality) + " " + o.jpgQuality);
                String s;
                if (o.format == Exporter.GIF) {
                    s = getString(R.string.gif_info, doc.frames.size(), doc.loop == 0 ? "∞" : String.valueOf(doc.loop));
                } else if (o.format == Exporter.PROJECT) {
                    s = getString(R.string.project_info);
                } else {
                    s = getString(R.string.frame_info, o.frame + 1);
                }
                info.setText(s);
            }
        };
        render[0].run();

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        final Dialog[] dlg = new Dialog[1];
        actions.addView(UI.button(this, getString(R.string.share), UI.SURFACE3, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg[0].dismiss();
                Exporter.Options c = copyOpts(o);
                runExport(c, null, new File(getCacheDir(), "export"));
            }
        }));
        actions.addView(UI.spacer(this, 8, 1));
        actions.addView(UI.button(this, getString(R.string.save), UI.ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg[0].dismiss();
                pendingExport = copyOpts(o);
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(Exporter.mime(o.format));
                i.putExtra(Intent.EXTRA_TITLE, safeName() + "." + Exporter.extension(o.format));
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingExport = null;
                    UI.toast(EditorActivity.this, R.string.no_app_found);
                }
            }
        }));
        LinearLayout.LayoutParams al = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = UI.dp(this, 14);
        box.addView(actions, al);
        dlg[0] = UI.dialog(this, getString(R.string.export), box, null, null, getString(R.string.close), null);
        dlg[0].show();
    }

    private TextView sectionLabel(int res) {
        TextView t = UI.label(this, getString(res), 12, UI.TEXT2);
        t.setPadding(0, UI.dp(this, 12), 0, UI.dp(this, 4));
        return t;
    }

    private void setChip(TextView t, boolean on) {
        t.setBackground(UI.rounded(on ? UI.ACCENT : UI.SURFACE2, 10, this));
        t.setTextColor(on ? Color.WHITE : UI.TEXT);
    }

    private static Exporter.Options copyOpts(Exporter.Options o) {
        Exporter.Options c = new Exporter.Options();
        c.format = o.format;
        c.scale = o.scale;
        c.background = o.background;
        c.jpgQuality = o.jpgQuality;
        c.frame = o.frame;
        return c;
    }

    private String safeName() {
        String n = projectName.replaceAll("[^\\p{L}\\p{N}_\\- ]", "").trim();
        return n.isEmpty() ? "pixel-studio" : n;
    }

    /** Renders on a worker thread. Writes to dest when given, otherwise to a cache file that is then shared. */
    private void runExport(final Exporter.Options o, final Uri dest, final File cacheDir) {
        final TextView msg = UI.label(this, getString(R.string.exporting), 15, UI.TEXT);
        final Dialog progress = UI.dialog(this, null, msg, null, null, null, null);
        progress.setCancelable(false);
        progress.show();
        new Thread(new Runnable() {
            @Override public void run() {
                File out = null;
                String error = null;
                try {
                    if (dest != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(dest, "wt")) {
                            if (os == null) throw new IOException("no stream");
                            Exporter.write(doc, o, os, null);
                        }
                    } else {
                        cacheDir.mkdirs();
                        File[] old = cacheDir.listFiles();
                        if (old != null) for (File f : old) f.delete();
                        out = new File(cacheDir, safeName() + "." + Exporter.extension(o.format));
                        try (OutputStream os = new FileOutputStream(out)) {
                            Exporter.write(doc, o, os, null);
                        }
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
                    if (t instanceof OutOfMemoryError) error = getString(R.string.out_of_memory);
                }
                final File fout = out;
                final String ferr = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        if (ferr != null) {
                            UI.toast(EditorActivity.this, getString(R.string.export_failed) + " " + ferr);
                        } else if (dest != null) {
                            UI.toast(EditorActivity.this, R.string.saved);
                        } else {
                            shareFile(fout, Exporter.mime(o.format));
                        }
                    }
                });
            }
        }).start();
    }

    private void shareFile(File f, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            UI.toast(this, R.string.no_app_found);
        }
    }

    // ------------------------------------------------------------------ color swatch

    private final class SwatchView extends View {
        private final Paint checker = ColorDialog.checkerPaint(EditorActivity.this, 6);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SwatchView(Context c) {
            super(c);
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ColorDialog.show(EditorActivity.this, ts.color, new ColorDialog.Result() {
                        @Override public void onColor(int c) {
                            setColor(c);
                            buildPalette();
                        }
                    });
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float m = UI.dp(getContext(), 2);
            RectF r = new RectF(m, m, w - m, h - m);
            float rad = UI.dp(getContext(), 9);
            c.drawRoundRect(r, rad, rad, checker);
            p.setStyle(Paint.Style.FILL);
            p.setColor(ts.color);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(UI.dp(getContext(), 2));
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
        }
    }
}
