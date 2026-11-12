package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A view that draws one of the built-in vector-style icons (24x24 design grid). */
public class IconView extends View {
    public static final int PENCIL = 1, BRUSH = 2, ERASER = 3, BUCKET = 4, LINE = 5, RECT = 6, CIRCLE = 7,
            SELECT = 8, MOVE = 9, HAND = 10, PICKER = 11, UNDO = 12, REDO = 13, LAYERS = 14, EXPORT = 15,
            GRID = 16, PLAY = 17, PAUSE = 18, ADD = 19, COPY = 20, TRASH = 21, ONION = 22, EYE = 23,
            EYE_OFF = 24, UP = 25, DOWN = 26, MORE = 27, BACK = 28, ZOOM_IN = 29, ZOOM_OUT = 30, FIT = 31,
            PREVIEW = 32, IMAGE = 33, CHECK = 34, MERGE = 35, CUT = 36, PASTE = 37, CLOSE = 38, LOOP = 39,
            LEFT = 40, RIGHT = 41, SETTINGS = 42, SAVE = 43, CLOCK = 44, FRAMES = 45, SELECT_ALL = 46,
            GLOBE = 47, EDIT = 48, SHARE = 49, FOLDER = 50, WAND = 51, SHADE = 52, PEN_TYPE = 53;

    private int icon;
    private int color = UI.TEXT;
    private boolean active;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    public IconView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(2f);
        fill.setStyle(Paint.Style.FILL);
        setClickable(true);
        setBackground(UI.ripple(null, 10, c));
    }

    public void setIcon(int icon) {
        this.icon = icon;
        invalidate();
    }

    public int getIcon() { return icon; }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    /** Active tools get an accent background. */
    public void setActive(boolean a) {
        active = a;
        setBackground(UI.ripple(a ? UI.rounded(UI.ACCENT, 10, getContext()) : null, 10, getContext()));
        invalidate();
    }

    public boolean isActive() { return active; }

    @Override
    public void setEnabled(boolean e) {
        super.setEnabled(e);
        setAlpha(e ? 1f : 0.35f);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void poly(Canvas c, boolean close, float... p) {
        path.reset();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        if (close) path.close();
        c.drawPath(path, stroke);
    }

    private void dot(Canvas c, float x, float y, float r, boolean solid) {
        c.drawCircle(x, y, r, solid ? fill : stroke);
    }

    @Override
    protected void onDraw(Canvas c) {
        int pl = getPaddingLeft(), pt = getPaddingTop();
        int pw = getWidth() - pl - getPaddingRight(), ph = getHeight() - pt - getPaddingBottom();
        float size = Math.min(pw, ph);
        if (size <= 0) return;
        int col = active ? 0xFFFFFFFF : color;
        stroke.setColor(col);
        fill.setColor(col);
        c.save();
        c.translate(pl + (pw - size) / 2f, pt + (ph - size) / 2f);
        float s = size / 24f;
        c.scale(s, s);
        stroke.setStrokeWidth(2f);
        stroke.setPathEffect(null);
        drawIcon(c);
        c.restore();
    }

    private void drawIcon(Canvas c) {
        switch (icon) {
            case PENCIL:
                poly(c, true, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19);
                line(c, 14, 6, 18, 10);
                break;
            case BRUSH:
                stroke.setStrokeWidth(4f);
                line(c, 11, 13, 19, 5);
                stroke.setStrokeWidth(2f);
                dot(c, 7.5f, 16.5f, 3.5f, true);
                break;
            case ERASER:
                poly(c, true, 7, 20, 3, 16, 13, 6, 19, 12, 11, 20);
                line(c, 7, 20, 21, 20);
                line(c, 8, 10, 14, 16);
                break;
            case BUCKET:
                poly(c, true, 5, 12, 11, 6, 17, 12, 11, 18);
                line(c, 9, 4, 12, 7);
                dot(c, 19, 17, 2f, true);
                break;
            case LINE:
                line(c, 5, 19, 19, 5);
                dot(c, 5, 19, 2f, true);
                dot(c, 19, 5, 2f, true);
                break;
            case RECT:
                rf.set(4, 6, 20, 18);
                c.drawRoundRect(rf, 2, 2, stroke);
                break;
            case CIRCLE:
                rf.set(3.5f, 5.5f, 20.5f, 18.5f);
                c.drawOval(rf, stroke);
                break;
            case SELECT:
                stroke.setPathEffect(new DashPathEffect(new float[]{3f, 2.5f}, 0));
                rf.set(4, 4, 20, 20);
                c.drawRect(rf, stroke);
                break;
            case SELECT_ALL:
                stroke.setPathEffect(new DashPathEffect(new float[]{3f, 2.5f}, 0));
                rf.set(3, 3, 21, 21);
                c.drawRect(rf, stroke);
                stroke.setPathEffect(null);
                rf.set(8, 8, 16, 16);
                c.drawRect(rf, fill);
                break;
            case MOVE:
                line(c, 12, 3, 12, 21);
                line(c, 3, 12, 21, 12);
                poly(c, false, 9, 6, 12, 3, 15, 6);
                poly(c, false, 9, 18, 12, 21, 15, 18);
                poly(c, false, 6, 9, 3, 12, 6, 15);
                poly(c, false, 18, 9, 21, 12, 18, 15);
                break;
            case HAND:
                rf.set(6.5f, 10, 17.5f, 21);
                c.drawRoundRect(rf, 3, 3, stroke);
                line(c, 8.5f, 10, 8.5f, 5);
                line(c, 11, 10, 11, 3.5f);
                line(c, 13.5f, 10, 13.5f, 4.5f);
                line(c, 16, 10, 16, 6);
                break;
            case PICKER:
                stroke.setStrokeWidth(3.5f);
                line(c, 5, 19, 13, 11);
                stroke.setStrokeWidth(2f);
                dot(c, 16.5f, 7.5f, 4f, false);
                line(c, 3.5f, 20.5f, 5.5f, 18.5f);
                break;
            case UNDO:
                poly(c, false, 9, 5, 4, 10, 9, 15);
                path.reset();
                path.moveTo(4, 10);
                path.lineTo(13, 10);
                path.cubicTo(18, 10, 20, 13, 20, 16);
                path.cubicTo(20, 18, 19, 19, 18, 20);
                c.drawPath(path, stroke);
                break;
            case REDO:
                poly(c, false, 15, 5, 20, 10, 15, 15);
                path.reset();
                path.moveTo(20, 10);
                path.lineTo(11, 10);
                path.cubicTo(6, 10, 4, 13, 4, 16);
                path.cubicTo(4, 18, 5, 19, 6, 20);
                c.drawPath(path, stroke);
                break;
            case LAYERS:
                poly(c, true, 12, 3, 21, 8, 12, 13, 3, 8);
                poly(c, false, 3, 12, 12, 17, 21, 12);
                poly(c, false, 3, 16, 12, 21, 21, 16);
                break;
            case EXPORT:
            case SHARE:
                line(c, 12, 15, 12, 3.5f);
                poly(c, false, 7.5f, 8, 12, 3.5f, 16.5f, 8);
                poly(c, false, 4, 13, 4, 20, 20, 20, 20, 13);
                break;
            case SAVE:
                poly(c, true, 4, 4, 17, 4, 20, 7, 20, 20, 4, 20);
                rf.set(8, 4, 15, 9);
                c.drawRect(rf, stroke);
                rf.set(7, 13, 17, 20);
                c.drawRect(rf, stroke);
                break;
            case GRID:
                rf.set(4, 4, 20, 20);
                c.drawRect(rf, stroke);
                line(c, 9.3f, 4, 9.3f, 20);
                line(c, 14.7f, 4, 14.7f, 20);
                line(c, 4, 9.3f, 20, 9.3f);
                line(c, 4, 14.7f, 20, 14.7f);
                break;
            case PLAY:
                path.reset();
                path.moveTo(7, 4);
                path.lineTo(20, 12);
                path.lineTo(7, 20);
                path.close();
                c.drawPath(path, fill);
                break;
            case PAUSE:
                rf.set(6, 4, 10.5f, 20);
                c.drawRect(rf, fill);
                rf.set(13.5f, 4, 18, 20);
                c.drawRect(rf, fill);
                break;
            case ADD:
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case COPY:
                rf.set(8, 8, 20, 20);
                c.drawRoundRect(rf, 2, 2, stroke);
                poly(c, false, 16, 8, 16, 5, 6, 5, 4, 7, 4, 16, 8, 16);
                break;
            case TRASH:
                line(c, 4, 7, 20, 7);
                poly(c, false, 9, 7, 9, 4, 15, 4, 15, 7);
                poly(c, false, 6, 7, 7, 20, 17, 20, 18, 7);
                line(c, 10, 11, 10, 16);
                line(c, 14, 11, 14, 16);
                break;
            case ONION:
                dot(c, 9, 12, 6, false);
                dot(c, 15, 12, 6, false);
                break;
            case EYE:
            case EYE_OFF:
                path.reset();
                path.moveTo(2, 12);
                path.cubicTo(6, 5, 18, 5, 22, 12);
                path.cubicTo(18, 19, 6, 19, 2, 12);
                c.drawPath(path, stroke);
                dot(c, 12, 12, 3, icon == EYE);
                if (icon == EYE_OFF) line(c, 4, 20, 20, 4);
                break;
            case UP:
                poly(c, false, 5, 15, 12, 8, 19, 15);
                break;
            case DOWN:
                poly(c, false, 5, 9, 12, 16, 19, 9);
                break;
            case LEFT:
                poly(c, false, 15, 5, 8, 12, 15, 19);
                break;
            case RIGHT:
                poly(c, false, 9, 5, 16, 12, 9, 19);
                break;
            case MORE:
                dot(c, 12, 5, 1.8f, true);
                dot(c, 12, 12, 1.8f, true);
                dot(c, 12, 19, 1.8f, true);
                break;
            case BACK:
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                    line(c, 4, 12, 19, 12);
                    poly(c, false, 13, 6, 19, 12, 13, 18);
                } else {
                    line(c, 20, 12, 5, 12);
                    poly(c, false, 11, 6, 5, 12, 11, 18);
                }
                break;
            case ZOOM_IN:
            case ZOOM_OUT:
                dot(c, 10, 10, 6.5f, false);
                line(c, 15, 15, 21, 21);
                line(c, 7, 10, 13, 10);
                if (icon == ZOOM_IN) line(c, 10, 7, 10, 13);
                break;
            case FIT:
                poly(c, false, 4, 9, 4, 4, 9, 4);
                poly(c, false, 15, 4, 20, 4, 20, 9);
                poly(c, false, 20, 15, 20, 20, 15, 20);
                poly(c, false, 9, 20, 4, 20, 4, 15);
                break;
            case PREVIEW:
                rf.set(3, 5, 21, 17);
                c.drawRoundRect(rf, 2, 2, stroke);
                line(c, 8, 21, 16, 21);
                line(c, 12, 17, 12, 21);
                break;
            case IMAGE:
                rf.set(3.5f, 4.5f, 20.5f, 19.5f);
                c.drawRoundRect(rf, 2, 2, stroke);
                poly(c, false, 4, 17, 9, 11, 13, 15, 16, 12, 20, 16);
                dot(c, 15.5f, 8.5f, 1.6f, true);
                break;
            case CHECK:
                poly(c, false, 5, 12.5f, 10, 17.5f, 19, 7);
                break;
            case MERGE:
                poly(c, false, 6, 4, 12, 10, 18, 4);
                line(c, 12, 10, 12, 20);
                poly(c, false, 8, 16, 12, 20, 16, 16);
                break;
            case CUT:
                dot(c, 7, 17, 3, false);
                dot(c, 17, 17, 3, false);
                line(c, 9, 15, 18, 4);
                line(c, 15, 15, 6, 4);
                break;
            case PASTE:
                rf.set(5, 5, 19, 21);
                c.drawRoundRect(rf, 2, 2, stroke);
                rf.set(9, 3, 15, 7);
                c.drawRoundRect(rf, 1.5f, 1.5f, stroke);
                break;
            case CLOSE:
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case LOOP:
                rf.set(4, 4, 20, 20);
                c.drawArc(rf, -60, 280, false, stroke);
                poly(c, false, 15, 3, 19.5f, 5.5f, 17, 10);
                break;
            case SETTINGS:
                dot(c, 12, 12, 3.5f, false);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    line(c, 12 + cs * 6.5f, 12 + sn * 6.5f, 12 + cs * 9f, 12 + sn * 9f);
                }
                dot(c, 12, 12, 6.5f, false);
                break;
            case CLOCK:
                dot(c, 12, 12, 9, false);
                poly(c, false, 12, 6.5f, 12, 12, 16, 14);
                break;
            case FRAMES:
                rf.set(3, 6, 21, 18);
                c.drawRoundRect(rf, 2, 2, stroke);
                line(c, 9, 6, 9, 18);
                line(c, 15, 6, 15, 18);
                break;
            case GLOBE:
                dot(c, 12, 12, 9, false);
                rf.set(8, 3, 16, 21);
                c.drawOval(rf, stroke);
                line(c, 3, 12, 21, 12);
                break;
            case EDIT:
                poly(c, true, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19);
                break;
            case FOLDER:
                poly(c, true, 3, 6, 9, 6, 11, 8, 21, 8, 21, 19, 3, 19);
                break;
            case WAND:
                stroke.setStrokeWidth(3.2f);
                line(c, 4.5f, 19.5f, 12.5f, 11.5f);
                stroke.setStrokeWidth(2f);
                line(c, 17, 3.5f, 17, 10.5f);
                line(c, 13.5f, 7, 20.5f, 7);
                dot(c, 20, 15, 1.3f, true);
                dot(c, 7.5f, 6, 1.3f, true);
                break;
            case SHADE:
                rf.set(4, 4, 20, 20);
                c.drawRoundRect(rf, 3, 3, stroke);
                stroke.setStrokeWidth(1.6f);
                line(c, 4, 13, 13, 4);
                line(c, 4, 20, 20, 4);
                line(c, 11, 20, 20, 11);
                stroke.setStrokeWidth(2f);
                break;
            case PEN_TYPE:
                poly(c, true, 4, 20, 5.5f, 14.5f, 15, 5, 19, 9, 9.5f, 18.5f);
                line(c, 13, 7, 17, 11);
                line(c, 14, 21, 21, 21);
                break;
            default:
                break;
        }
    }
}
