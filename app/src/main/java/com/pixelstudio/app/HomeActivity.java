package com.pixelstudio.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Templates;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Home screen: recent projects, new project, open image/GIF/project, templates, language. */
public class HomeActivity extends Activity {
    private static final int REQ_OPEN = 21;

    private LinearLayout content;
    private ScrollView scroll;
    private int lastColumns = -1;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Settings.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(UI.BG);
        getWindow().setNavigationBarColor(UI.BG);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(UI.BG);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UI.dp(this, 16);
        content.setPadding(p, p, p, p * 2);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        build();
    }

    private int columns() {
        int w = getResources().getDisplayMetrics().widthPixels - UI.dp(this, 32);
        return Math.max(2, Math.min(6, w / UI.dp(this, 160)));
    }

    private void build() {
        content.removeAllViews();
        lastColumns = columns();

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LogoView logo = new LogoView(this);
        head.addView(logo, UI.lp(this, 46, 46));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), 0);
        titles.addView(UI.bold(this, getString(R.string.app_name), 24, UI.TEXT));
        titles.addView(UI.label(this, getString(R.string.tagline), 13, UI.TEXT2));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        IconView lang = UI.iconButton(this, IconView.GLOBE, 44, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Settings.setLanguage(HomeActivity.this, Settings.isArabic(HomeActivity.this) ? "en" : "ar");
                recreate();
            }
        });
        lang.setBackground(UI.rounded(UI.SURFACE2, 12, this));
        head.addView(lang);
        content.addView(head);

        // main actions
        LinearLayout actions = new LinearLayout(this);
        TextView nw = UI.button(this, "＋  " + getString(R.string.new_project), UI.ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) { showNewProject(); }
        });
        nw.setTextSize(16);
        nw.setPadding(nw.getPaddingLeft(), UI.dp(this, 16), nw.getPaddingRight(), UI.dp(this, 16));
        TextView open = UI.button(this, getString(R.string.open_image), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { openFile(); }
        });
        open.setTextSize(16);
        open.setPadding(open.getPaddingLeft(), UI.dp(this, 16), open.getPaddingRight(), UI.dp(this, 16));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a1.rightMargin = UI.dp(this, 10);
        actions.addView(nw, a1);
        actions.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams al = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = UI.dp(this, 20);
        content.addView(actions, al);

        // recent projects
        content.addView(sectionTitle(R.string.recent_projects));
        List<ProjectStore.Info> list = ProjectStore.list(this);
        if (list.isEmpty()) {
            TextView empty = UI.label(this, getString(R.string.no_projects), 14, UI.TEXT2);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UI.dp(this, 28), 0, UI.dp(this, 28));
            empty.setBackground(UI.roundedStroke(UI.SURFACE, UI.SURFACE3, 1, 14, this));
            content.addView(empty, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            GridLayout grid = new GridLayout(this);
            int cols = lastColumns;
            grid.setColumnCount(cols);
            int cardW = (getResources().getDisplayMetrics().widthPixels - UI.dp(this, 32) - cols * UI.dp(this, 10)) / cols;
            for (ProjectStore.Info i : list) {
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = cardW;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.setMargins(UI.dp(this, 5), UI.dp(this, 5), UI.dp(this, 5), UI.dp(this, 5));
                grid.addView(projectCard(i, cardW), lp);
            }
            LinearLayout.LayoutParams gl = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gl.leftMargin = -UI.dp(this, 5);
            content.addView(grid, gl);
        }

        // templates
        content.addView(sectionTitle(R.string.templates));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        String[] names = {getString(R.string.tpl_heart), getString(R.string.tpl_smiley), getString(R.string.tpl_mushroom),
                getString(R.string.tpl_invader), getString(R.string.tpl_sword), getString(R.string.tpl_blank)};
        for (int i = 0; i < Templates.COUNT; i++) {
            row.addView(templateCard(i, names[i]));
        }
        hs.addView(row);
        content.addView(hs);
    }

    private TextView sectionTitle(int res) {
        TextView t = UI.bold(this, getString(res), 16, UI.TEXT);
        t.setPadding(0, UI.dp(this, 24), 0, UI.dp(this, 10));
        return t;
    }

    // ------------------------------------------------------------------ cards

    private View projectCard(final ProjectStore.Info info, int cardW) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UI.roundedStroke(UI.SURFACE, UI.SURFACE3, 1, 14, this));
        card.setPadding(UI.dp(this, 8), UI.dp(this, 8), UI.dp(this, 8), UI.dp(this, 8));
        ThumbView tv = new ThumbView(this, info);
        card.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (cardW * 0.72f)));
        TextView name = UI.bold(this, info.name, 14, UI.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, UI.dp(this, 8), 0, 0);
        card.addView(name);
        String details = info.w + "×" + info.h + (info.frames > 1 ? " · " + info.frames + " " + getString(R.string.frames) : "");
        TextView sub = UI.label(this, details, 12, UI.TEXT2);
        card.addView(sub);
        TextView date = UI.label(this, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(info.modified)), 11, UI.TEXT2);
        date.setSingleLine(true);
        card.addView(date);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openProject(info.id); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { projectMenu(info); return true; }
        });
        return card;
    }

    private void projectMenu(final ProjectStore.Info info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dlg = new Dialog[1];
        String[] labels = {getString(R.string.open), getString(R.string.rename), getString(R.string.duplicate), getString(R.string.delete)};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = UI.label(this, labels[i], 15, idx == 3 ? UI.DANGER : UI.TEXT);
            t.setPadding(UI.dp(this, 12), UI.dp(this, 13), UI.dp(this, 12), UI.dp(this, 13));
            t.setBackground(UI.rounded(UI.SURFACE2, 10, this));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg[0].dismiss();
                    switch (idx) {
                        case 0: openProject(info.id); break;
                        case 1: renameProject(info); break;
                        case 2: duplicateProject(info); break;
                        default: deleteProject(info); break;
                    }
                }
            });
            LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UI.dp(this, 6);
            box.addView(t, lp);
        }
        dlg[0] = UI.dialog(this, info.name, box, null, null, getString(R.string.close), null);
        dlg[0].show();
    }

    private void renameProject(final ProjectStore.Info info) {
        UI.promptText(this, getString(R.string.rename), info.name, getString(R.string.ok), getString(R.string.cancel),
                new UI.TextResult() {
                    @Override public void onText(String s) {
                        if (s.isEmpty()) return;
                        ProjectStore.rename(HomeActivity.this, info.id, s);
                        build();
                    }
                });
    }

    private void duplicateProject(ProjectStore.Info info) {
        try {
            ProjectStore.duplicate(this, info.id, info.name + " " + getString(R.string.copy_suffix));
            build();
        } catch (IOException | RuntimeException e) {
            UI.toast(this, R.string.save_failed);
        }
    }

    private void deleteProject(final ProjectStore.Info info) {
        UI.confirm(this, getString(R.string.delete), getString(R.string.delete_confirm, info.name),
                getString(R.string.delete), getString(R.string.cancel), new UI.Action() {
                    @Override public void run() {
                        ProjectStore.delete(HomeActivity.this, info.id);
                        build();
                    }
                });
    }

    private View templateCard(final int idx, String name) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(UI.roundedStroke(UI.SURFACE, UI.SURFACE3, 1, 14, this));
        int p = UI.dp(this, 8);
        card.setPadding(p, p, p, p);
        Doc d = Templates.build(idx, "x");
        TemplateThumb tv = new TemplateThumb(this, d);
        card.addView(tv, new LinearLayout.LayoutParams(UI.dp(this, 84), UI.dp(this, 84)));
        TextView t = UI.label(this, name, 12, UI.TEXT);
        t.setPadding(0, UI.dp(this, 6), 0, 0);
        t.setSingleLine(true);
        card.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UI.dp(this, 104), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(this, 10);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { createFromTemplate(idx); }
        });
        return card;
    }

    // ------------------------------------------------------------------ project creation

    private void createFromTemplate(int idx) {
        Doc d = Templates.build(idx, getString(R.string.layer) + " 1");
        d.bg = 0;
        String[] names = {getString(R.string.tpl_heart), getString(R.string.tpl_smiley), getString(R.string.tpl_mushroom),
                getString(R.string.tpl_invader), getString(R.string.tpl_sword), getString(R.string.tpl_blank)};
        createAndOpen(d, names[idx]);
    }

    private void createAndOpen(Doc d, String name) {
        String id = ProjectStore.newId();
        try {
            ProjectStore.save(this, id, name, d);
        } catch (IOException | RuntimeException e) {
            UI.toast(this, R.string.save_failed);
            return;
        }
        openProject(id);
    }

    private void openProject(String id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private TextView chip(String text, boolean on) {
        TextView t = UI.button(this, text, on ? UI.ACCENT : UI.SURFACE2, on ? Color.WHITE : UI.TEXT, null);
        t.setTextSize(13);
        return t;
    }

    private void showNewProject() {
        final boolean[] pixel = {true};
        final int[] size = {32, 32};
        final boolean[] transparent = {true};
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];

        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setText(getString(R.string.untitled));
        nameEt.setSelectAllOnFocus(true);
        nameEt.setTextColor(UI.TEXT);
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEt.setBackground(UI.roundedStroke(UI.SURFACE2, UI.SURFACE3, 1, 10, this));
        int pd = UI.dp(this, 12);
        nameEt.setPadding(pd, pd, pd, pd);

        final LinearLayout modeRow = new LinearLayout(this);
        final LinearLayout presetRow = new LinearLayout(this);
        final LinearLayout bgRow = new LinearLayout(this);
        final LinearLayout customRow = new LinearLayout(this);
        final EditText wEt = numberField(size[0]);
        final EditText hEt = numberField(size[1]);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.addView(wEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = UI.label(this, "  ×  ", 16, UI.TEXT2);
        customRow.addView(x);
        customRow.addView(hEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        render[0] = new Runnable() {
            @Override public void run() {
                modeRow.removeAllViews();
                presetRow.removeAllViews();
                bgRow.removeAllViews();
                modeRow.addView(modeChip(getString(R.string.mode_pixel), pixel[0], new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!pixel[0]) { pixel[0] = true; size[0] = 32; size[1] = 32; syncFields(wEt, hEt, size); render[0].run(); }
                    }
                }));
                modeRow.addView(modeChip(getString(R.string.mode_draw), !pixel[0], new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (pixel[0]) { pixel[0] = false; size[0] = 1080; size[1] = 1080; transparent[0] = false; syncFields(wEt, hEt, size); render[0].run(); }
                    }
                }));
                int[][] presets = pixel[0]
                        ? new int[][]{{16, 16}, {32, 32}, {64, 64}, {128, 128}, {64, 32}, {32, 64}}
                        : new int[][]{{512, 512}, {1080, 1080}, {1080, 1920}, {1920, 1080}, {2048, 2048}};
                for (final int[] pr : presets) {
                    boolean on = size[0] == pr[0] && size[1] == pr[1];
                    TextView c = chip(pr[0] + "×" + pr[1], on);
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            size[0] = pr[0]; size[1] = pr[1];
                            syncFields(wEt, hEt, size);
                            render[0].run();
                        }
                    });
                    LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = UI.dp(HomeActivity.this, 6);
                    lp.bottomMargin = UI.dp(HomeActivity.this, 6);
                    presetRow.addView(c, lp);
                }
                bgRow.addView(modeChip(getString(R.string.transparent), transparent[0], new View.OnClickListener() {
                    @Override public void onClick(View v) { transparent[0] = true; render[0].run(); }
                }));
                bgRow.addView(modeChip(getString(R.string.white), !transparent[0], new View.OnClickListener() {
                    @Override public void onClick(View v) { transparent[0] = false; render[0].run(); }
                }));
            }
        };
        render[0].run();

        box.addView(UI.label(this, getString(R.string.name), 12, UI.TEXT2));
        box.addView(nameEt);
        box.addView(spaced(UI.label(this, getString(R.string.type), 12, UI.TEXT2)));
        box.addView(modeRow);
        box.addView(spaced(UI.label(this, getString(R.string.canvas_size), 12, UI.TEXT2)));
        // wrap chips
        HorizontalScrollView ps = new HorizontalScrollView(this);
        ps.setHorizontalScrollBarEnabled(false);
        ps.addView(presetRow);
        box.addView(ps);
        box.addView(customRow);
        box.addView(spaced(UI.label(this, getString(R.string.background), 12, UI.TEXT2)));
        box.addView(bgRow);

        final Dialog d = UI.dialog(this, getString(R.string.new_project), box, getString(R.string.create), new UI.Action() {
            @Override public void run() {
                int w = parse(wEt, size[0]), h = parse(hEt, size[1]);
                int max = pixel[0] ? 512 : Doc.MAX_SIDE;
                w = Math.max(1, Math.min(max, w));
                h = Math.max(1, Math.min(max, h));
                if ((long) w * h > 4096L * 2048L) {
                    UI.toast(HomeActivity.this, R.string.too_large);
                    return;
                }
                Doc doc = Doc.create(w, h, pixel[0], transparent[0] ? 0 : 0xFFFFFFFF, getString(R.string.layer) + " 1");
                String n = nameEt.getText().toString().trim();
                createAndOpen(doc, n.isEmpty() ? getString(R.string.untitled) : n);
            }
        }, getString(R.string.cancel), null);
        d.show();
    }

    private View spaced(View v) {
        v.setPadding(0, UI.dp(this, 14), 0, UI.dp(this, 6));
        return v;
    }

    private TextView modeChip(String text, boolean on, View.OnClickListener l) {
        TextView t = chip(text, on);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = UI.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText numberField(int v) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(String.valueOf(v));
        e.setTextColor(UI.TEXT);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setBackground(UI.roundedStroke(UI.SURFACE2, UI.SURFACE3, 1, 10, this));
        int p = UI.dp(this, 10);
        e.setPadding(p, p, p, p);
        return e;
    }

    private static void syncFields(EditText w, EditText h, int[] size) {
        w.setText(String.valueOf(size[0]));
        h.setText(String.valueOf(size[1]));
    }

    private static int parse(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------------ open file

    /** Photos, GIFs and videos open the phone gallery; project files (.pxs) still need the file picker. */
    private void openFile() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dd = new Dialog[1];
        View.OnClickListener gallery = new View.OnClickListener() {
            @Override public void onClick(View v) {
                dd[0].dismiss();
                MediaPicker.pick(HomeActivity.this, REQ_OPEN, false);
            }
        };
        View.OnClickListener files = new View.OnClickListener() {
            @Override public void onClick(View v) {
                dd[0].dismiss();
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException e) {
                    UI.toast(HomeActivity.this, R.string.no_app_found);
                }
            }
        };
        int[] texts = {R.string.from_gallery, R.string.from_files};
        View.OnClickListener[] ls = {gallery, files};
        for (int k = 0; k < 2; k++) {
            TextView t = UI.button(this, getString(texts[k]), k == 0 ? UI.ACCENT : UI.SURFACE2, k == 0 ? Color.WHITE : UI.TEXT, ls[k]);
            LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UI.dp(this, 8);
            box.addView(t, lp);
        }
        dd[0] = UI.dialog(this, getString(R.string.choose_source), box, null, null, getString(R.string.cancel), null);
        dd[0].show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_OPEN || res != RESULT_OK || data == null) return;
        Uri picked = data.getData();
        if (picked == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            picked = data.getClipData().getItemAt(0).getUri();
        }
        if (picked == null) return;
        final Uri uri = picked;
        final Busy busy = Busy.show(this, getString(R.string.opening), true);
        new Thread(new Runnable() {
            @Override public void run() {
                ImportUtil.Loaded l = null;
                String err = null;
                boolean cancelled = false;
                try {
                    l = ImportUtil.load(HomeActivity.this, uri, Doc.MAX_SIDE, new ImportUtil.Progress() {
                        @Override public void onProgress(int percent) { busy.progress(percent); }
                        @Override public boolean cancelled() { return busy.cancelled(); }
                    });
                } catch (ImportUtil.CancelledException t) {
                    cancelled = true;
                } catch (Throwable t) {
                    err = t instanceof OutOfMemoryError ? getString(R.string.out_of_memory) : getString(R.string.open_failed);
                }
                final ImportUtil.Loaded fl = l;
                final String ferr = err;
                final boolean fcancel = cancelled || busy.cancelled();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy.dismiss();
                        if (isFinishing() || fcancel) return;
                        if (fl == null) {
                            UI.toast(HomeActivity.this, ferr == null ? getString(R.string.open_failed) : ferr);
                            return;
                        }
                        if (fl.project != null) {
                            createAndOpen(fl.project, getString(R.string.imported));
                            return;
                        }
                        if (fl.media.trimmed) UI.toast(HomeActivity.this, R.string.video_trimmed);
                        if (fl.media.animated()) {
                            // GIF / video: let the person choose one frame or all of them
                            FramePicker.show(HomeActivity.this, fl.media, new FramePicker.Result() {
                                @Override public void onPick(int frame) { openMedia(fl.media, frame); }
                            });
                        } else {
                            openMedia(fl.media, -1);
                        }
                    }
                });
            }
        }).start();
    }

    private void openMedia(ImportUtil.Media m, int frame) {
        try {
            createAndOpen(ImportUtil.docFromMedia(m, frame, getString(R.string.layer) + " 1"), getString(R.string.imported));
        } catch (Throwable t) {
            UI.toast(this, t instanceof OutOfMemoryError ? getString(R.string.out_of_memory) : getString(R.string.open_failed));
        }
    }

    // ------------------------------------------------------------------ small views

    private static final class LogoView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        LogoView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float g = w / 4f;
            int[] cols = {UI.ACCENT, UI.ACCENT2, 0xFFFFD166, 0xFFFF5470};
            int[][] map = {{0, 1, 1, 0}, {1, 2, 2, 1}, {1, 2, 3, 1}, {0, 1, 1, 0}};
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    p.setColor(cols[map[y][x]]);
                    float m = w * 0.02f;
                    c.drawRoundRect(new RectF(x * g + m, y * g + m, (x + 1) * g - m, (y + 1) * g - m), g * 0.2f, g * 0.2f, p);
                }
            }
        }
    }

    private static final class ThumbView extends View {
        private final Paint checker;
        private final Paint paint = new Paint();
        private Bitmap bmp;
        private final boolean pixel;
        private final RectF r = new RectF();

        ThumbView(Context c, ProjectStore.Info info) {
            super(c);
            checker = ColorDialog.checkerPaint(c, 7);
            pixel = info.pixel;
            if (info.thumb != null) {
                try {
                    bmp = BitmapFactory.decodeFile(info.thumb.getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF all = new RectF(0, 0, w, h);
            float rad = UI.dp(getContext(), 8);
            c.save();
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(UI.SURFACE2);
            c.drawRoundRect(all, rad, rad, bg);
            if (bmp != null) {
                float s = Math.min((w - 12) / bmp.getWidth(), (h - 12) / bmp.getHeight());
                float tw = bmp.getWidth() * s, th = bmp.getHeight() * s;
                r.set((w - tw) / 2f, (h - th) / 2f, (w + tw) / 2f, (h + th) / 2f);
                c.drawRect(r, checker);
                paint.setFilterBitmap(!pixel);
                c.drawBitmap(bmp, null, r, paint);
            }
            c.restore();
        }
    }

    private static final class TemplateThumb extends View {
        private final Paint checker;
        private final Paint paint = new Paint();
        private final Bitmap bmp;

        TemplateThumb(Context c, Doc d) {
            super(c);
            checker = ColorDialog.checkerPaint(c, 6);
            int[] px = d.compose(d.frames.get(0));
            bmp = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, d.w, 0, 0, d.w, d.h);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF r = new RectF(0, 0, w, h);
            c.drawRect(r, checker);
            paint.setFilterBitmap(false);
            c.drawBitmap(bmp, null, r, paint);
        }
    }
}
