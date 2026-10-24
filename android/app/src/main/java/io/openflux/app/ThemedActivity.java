package io.openflux.app;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.WeakHashMap;

// The app's look in one place: the palette and the building blocks every
// screen is made of (text, tiles, switches, choice lists, outlined fields
// with a floating label, press and reveal motion). Screens are built in
// code from these, so a change of style here reaches all of them.
abstract class ThemedActivity extends Activity {
    // ---- Shell hooks ----

    // What the app's shell provides: the room the bottom bar takes, and the
    // page container an animated show/hide reflows.
    abstract int navClearance();

    abstract ViewGroup transitionRoot(View view);

    // ---- Palette ----

    boolean darkMode;
    int background;
    int surface;
    int text;
    int secondary;
    int border;
    int accent;
    int hint;
    int logColor;

    boolean isSystemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    void applyPalette() {
        if (darkMode) {
            // Deep navy dark theme matching upstream's OpenFluxAndroid palette
            // (bg_deep/bg_card/bg_card_stroke/accent_blue) rather than a
            // neutral grey dark mode.
            background = Color.rgb(8, 11, 18);
            surface = Color.rgb(19, 23, 34);
            text = Color.WHITE;
            secondary = Color.rgb(138, 146, 166);
            border = Color.rgb(42, 52, 70);
            accent = Color.rgb(79, 124, 255);
            hint = Color.rgb(90, 98, 114);
            logColor = Color.rgb(218, 220, 224);
        } else {
            background = Color.rgb(248, 249, 250);
            surface = Color.WHITE;
            text = Color.rgb(32, 33, 36);
            secondary = Color.rgb(95, 99, 104);
            border = Color.rgb(218, 220, 224);
            accent = Color.rgb(79, 124, 255);
            hint = Color.rgb(128, 134, 139);
            logColor = Color.rgb(60, 64, 67);
        }
    }

    // accent laid over surface at the given strength: the tonal fill of a
    // selected choice.
    int tonal(float strength) {
        return Color.rgb(
                (int) (Color.red(surface) + (Color.red(accent) - Color.red(surface)) * strength),
                (int) (Color.green(surface) + (Color.green(accent) - Color.green(surface)) * strength),
                (int) (Color.blue(surface) + (Color.blue(accent) - Color.blue(surface)) * strength));
    }

    // ---- Layout ----

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -1, 1f); }

    LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    View wrapScroll(View sectionContent) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(sectionContent, new ScrollView.LayoutParams(-1, -2));
        scrollBottom(scroll, navClearance());
        return scroll;
    }

    // The room under a scroll's content goes inside the content, not on the
    // ScrollView: a ScrollView counts its own padding as visible area when it
    // decides whether it can scroll, so with less overflow than the padding a
    // drag that starts on a button or a field would not move it.
    static void scrollBottom(View scroll, int bottom) {
        View content = ((ScrollView) scroll).getChildAt(0);
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), bottom);
    }

    void addDivider(LinearLayout parent) {
        addDivider(parent, dp(56));
    }

    void addDivider(LinearLayout parent, int leftMargin) {
        View line = new View(this);
        line.setBackgroundColor(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = leftMargin;
        parent.addView(line, params);
    }

    // Lists of tiles (settings sections, choices, app rows) are separate
    // cards: the same corners all round and a gap between them.
    static final int TILE_RADIUS = 16;
    static final int TILE_GAP = 8;
    // Above a section title, except at the top of a page.
    static final int SECTION_GAP = 24;

    // Spacing on every settings-like page, so no page needs its own numbers:
    //   intro text   - 16dp to what follows it (introText);
    //   section      - uppercase title, SECTION_GAP above it (sectionTitle);
    //   tile         - TILE_GAP above it, after a title, a tile or a hint (tileParams);
    //   hint         - 6dp under the tile it explains (fieldHint).

    // A page's opening paragraph.
    TextView introText(CharSequence value) {
        TextView intro = text(value, 12, secondary, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(16 - TILE_GAP);
        intro.setLayoutParams(params);
        return intro;
    }

    // An uppercase section title for the end of parent: SECTION_GAP from
    // what is above (less any room that already leaves below itself); the
    // tile after it brings its own TILE_GAP.
    TextView sectionTitle(LinearLayout parent, String title) {
        TextView label = label(title);
        LinearLayout.LayoutParams params = matchWrap();
        int n = parent.getChildCount();
        if (n > 0) {
            ViewGroup.LayoutParams last = parent.getChildAt(n - 1).getLayoutParams();
            int below = last instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) last).bottomMargin : 0;
            params.topMargin = Math.max(0, dp(SECTION_GAP) - below);
        }
        label.setLayoutParams(params);
        return label;
    }

    LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(TILE_GAP);
        return params;
    }

    // A floating-label field (floating(), iconTextField) as a tile: TILE_GAP
    // above it like any tile, plus its own FLOAT_ROOM for the label on the
    // border, so the label clears what is above by about as much as a tile.
    LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        params.topMargin = dp(TILE_GAP);
        return params;
    }

    // Lays out the children of list as separate cards, each with its own ripple.
    void groupTiles(LinearLayout list) {
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = list.getChildAt(i);
            child.setBackground(tile(surface, border, radii(TILE_RADIUS)));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.topMargin = i == 0 ? 0 : dp(TILE_GAP);
            child.setLayoutParams(params);
        }
    }

    // ---- Text and icons ----

    TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    TextView label(String value) {
        TextView view = text(value, 11, secondary, true);
        view.setLetterSpacing(0.08f);
        return view;
    }

    TextView fieldHint(String value) {
        TextView hint = text(value, 11, secondary, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        hint.setLayoutParams(params);
        return hint;
    }

    ImageView icon(int resource, int color) {
        ImageView view = new ImageView(this);
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(color));
        return view;
    }

    ImageButton iconButton(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setImageTintList(ColorStateList.valueOf(secondary));
        button.setContentDescription(description);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(ripple(Color.TRANSPARENT, 24));
        return button;
    }

    // Icon on a tonal circle, as in the Android 15-16 settings lists.
    View iconBubble(int iconRes) {
        return iconBubble(iconRes, false);
    }

    View iconBubble(int iconRes, boolean activeProfile) {
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(activeProfile ? (darkMode ? Color.rgb(29, 66, 49) : Color.rgb(222, 245, 229))
                : tonal(0.2f));
        bubble.setBackground(circle);
        int iconSize = iconRes == R.drawable.ic_yandex ? 18 : 22;
        bubble.addView(icon(iconRes, activeProfile
                        ? (darkMode ? Color.rgb(112, 219, 147) : Color.rgb(31, 143, 70)) : accent),
                new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER));
        return bubble;
    }

    // ---- Shapes and backgrounds ----

    GradientDrawable rounded(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    RippleDrawable ripple(int fill, int radius) {
        // No ripple highlight color - it showed as a flat grey/tinted box
        // over the whole rounded touch target instead of a subtle effect.
        // Feedback on tap comes from tap()/bounce() (haptics + scale) instead.
        return new RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT),
                rounded(fill, Color.TRANSPARENT, 0, radius), rounded(Color.WHITE, Color.TRANSPARENT, 0, radius));
    }

    RippleDrawable buttonBackground(int fill, int pressed) {
        // The same corners as tiles, fields and tonal buttons.
        return new RippleDrawable(ColorStateList.valueOf(pressed), rounded(fill, Color.TRANSPARENT, 0, TILE_RADIUS),
                rounded(Color.WHITE, Color.TRANSPARENT, 0, TILE_RADIUS));
    }

    android.graphics.drawable.Drawable tile(int fill, int strokeColor, float[] radii) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadii(radii);
        shape.setStroke(dp(1), strokeColor);
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))),
                shape, null);
    }

    float[] radii(int radius) {
        float r = dp(radius);
        return new float[]{r, r, r, r, r, r, r, r};
    }

    RippleDrawable circleBackground(int fill, int pressed) {
        return new RippleDrawable(ColorStateList.valueOf(pressed), rounded(fill, Color.TRANSPARENT, 0, 70),
                rounded(Color.WHITE, Color.TRANSPARENT, 0, 70));
    }

    // ---- Controls ----

    Button primaryButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setStateListAnimator(null);
        button.setBackground(buttonBackground(Color.rgb(79, 124, 255), Color.rgb(59, 93, 191)));
        button.setOnClickListener(v -> {
            bounce(v);
            action.run();
        });
        return button;
    }

    ImageView choiceCheck() {
        ImageView check = icon(R.drawable.ic_check, Color.WHITE);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        check.setBackground(dot);
        check.setPadding(dp(3), dp(3), dp(3), dp(3));
        return check;
    }

    // Single choice as separate tiles: the picked one gets a tonal fill and
    // a check. options[i] is {title} or {title, subtitle}.
    View choiceList(String[][] options, int[] icons, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int n = options.length;
        LinearLayout[] rows = new LinearLayout[n];
        TextView[] titles = new TextView[n];
        ImageView[] checks = new ImageView[n];
        int[] current = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < n; i++) {
                boolean on = i == current[0];
                rows[i].setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                        radii(TILE_RADIUS)));
                titles[i].setTextColor(on ? accent : text);
                ImageView check = checks[i];
                check.animate().cancel();
                if (on && check.getVisibility() != View.VISIBLE) {
                    check.setVisibility(View.VISIBLE);
                    check.setScaleX(0f);
                    check.setScaleY(0f);
                    check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(1.6f)).start();
                } else if (!on) {
                    check.setVisibility(View.INVISIBLE);
                }
            }
        };
        for (int i = 0; i < n; i++) {
            int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(60));
            row.setPadding(dp(18), dp(12), dp(16), dp(12));
            if (icons != null && icons[i] != 0) {
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                iconParams.rightMargin = dp(14);
                row.addView(iconBubble(icons[i]), iconParams);
                row.setPadding(dp(14), dp(12), dp(16), dp(12));
            }
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            titles[i] = text(options[i][0], 15, text, true);
            copy.addView(titles[i]);
            if (options[i].length > 1) copy.addView(text(options[i][1], 12, secondary, false));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            checks[i] = choiceCheck();
            checks[i].setVisibility(i == selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(checks[i], new LinearLayout.LayoutParams(dp(24), dp(24)));
            row.setOnClickListener(v -> {
                if (current[0] == index) return;
                tap(v);
                current[0] = index;
                paint.run();
                onPick.accept(index);
            });
            rows[i] = row;
            LinearLayout.LayoutParams params = matchWrap();
            if (i > 0) params.topMargin = dp(TILE_GAP);
            list.addView(row, params);
        }
        paint.run();
        return list;
    }

    // Material 3 segmented button: one pill split into equal segments, the
    // picked one tonal with a leading check.
    View segmented(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout bar = new LinearLayout(this);
        int n = labels.length;
        LinearLayout[] segments = new LinearLayout[n];
        TextView[] titles = new TextView[n];
        ImageView[] checks = new ImageView[n];
        int[] current = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < n; i++) {
                boolean on = i == current[0];
                float left = i == 0 ? dp(24) : 0, right = i == n - 1 ? dp(24) : 0;
                segments[i].setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                        new float[]{left, left, right, right, right, right, left, left}));
                titles[i].setTextColor(on ? accent : text);
                checks[i].setVisibility(on ? View.VISIBLE : View.GONE);
            }
        };
        for (int i = 0; i < n; i++) {
            int index = i;
            LinearLayout segment = new LinearLayout(this);
            segment.setGravity(Gravity.CENTER);
            checks[i] = icon(R.drawable.ic_check, accent);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(18), dp(18));
            checkParams.rightMargin = dp(6);
            segment.addView(checks[i], checkParams);
            titles[i] = text(labels[i], 14, text, true);
            segment.addView(titles[i]);
            segment.setOnClickListener(v -> {
                if (current[0] == index) return;
                tap(v);
                current[0] = index;
                android.transition.TransitionManager.beginDelayedTransition(bar);
                paint.run();
                onPick.accept(index);
            });
            segments[i] = segment;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) params.leftMargin = -dp(1);
            bar.addView(segment, params);
        }
        paint.run();
        return bar;
    }

    // A horizontal row of filter chips, one picked.
    View chips(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        scroll.addView(row);
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            boolean on = i == selected;
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(on ? 10 : 14), 0, dp(14), 0);
            float r = dp(10);
            chip.setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                    new float[]{r, r, r, r, r, r, r, r}));
            if (on) {
                LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(16), dp(16));
                checkParams.rightMargin = dp(6);
                chip.addView(icon(R.drawable.ic_check, accent), checkParams);
            }
            chip.addView(text(labels[i], 13, on ? accent : text, on));
            chip.setOnClickListener(v -> {
                if (index == selected) return;
                tap(v);
                onPick.accept(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) params.leftMargin = dp(8);
            row.addView(chip, params);
        }
        return scroll;
    }

    // Material 3 switch: a 56x32 pill track; off, an outlined track with a
    // small thumb; on, a filled track with a large thumb carrying a check.
    void styleSwitch(Switch toggle) {
        // Off is a filled grey-blue track with a plain thumb of the same size
        // as the on one: switching changes only the color, not the shape.
        int offTrack = darkMode ? Color.rgb(58, 68, 88) : Color.rgb(205, 212, 226);
        int offThumb = darkMode ? Color.rgb(176, 184, 198) : Color.WHITE;
        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setCornerRadius(dp(14));
        trackOn.setColor(accent);
        trackOn.setSize(dp(48), dp(28));
        GradientDrawable trackOff = new GradientDrawable();
        trackOff.setCornerRadius(dp(14));
        trackOff.setColor(offTrack);
        trackOff.setSize(dp(48), dp(28));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);
        track.setEnterFadeDuration(150);
        track.setExitFadeDuration(150);

        // Both thumbs are 24x28 (the track is twice as wide). The circle is
        // nudged 2dp toward the middle, so it sits on the track end's center
        // with the same 4dp all round as above and below it.
        android.graphics.drawable.Drawable thumbOn = switchThumb(dp(10), -dp(2), Color.WHITE, accent);
        android.graphics.drawable.Drawable thumbOff = switchThumb(dp(10), dp(2), offThumb, 0);
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(new int[]{}, thumbOff);
        thumb.setEnterFadeDuration(150);
        thumb.setExitFadeDuration(150);

        toggle.setTrackDrawable(track);
        toggle.setThumbDrawable(thumb);
        toggle.setTrackTintList(null);
        toggle.setThumbTintList(null);
        toggle.setSwitchMinWidth(dp(48));
        toggle.setShowText(false);
        toggle.setBackground(null);
    }

    // A switch thumb: a circle of the given radius centered in 24x28, with
    // a check drawn in checkColor when that is not 0.
    android.graphics.drawable.Drawable switchThumb(float radius, float shiftX, int fill, int checkColor) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();
            @Override public void draw(android.graphics.Canvas canvas) {
                float cx = getBounds().exactCenterX() + shiftX, cy = getBounds().exactCenterY();
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(fill);
                canvas.drawCircle(cx, cy, radius, paint);
                if (checkColor == 0) return;
                float u = radius / 12f;
                path.reset();
                path.moveTo(cx - 5 * u, cy);
                path.lineTo(cx - 1.5f * u, cy + 3.5f * u);
                path.lineTo(cx + 5.5f * u, cy - 3.5f * u);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f * u);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                paint.setColor(checkColor);
                canvas.drawPath(path, paint);
            }
            @Override public int getIntrinsicWidth() { return dp(24); }
            @Override public int getIntrinsicHeight() { return dp(28); }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    Switch settingSwitch(int iconRes, String titleValue, String detailValue, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(tile(surface, border, radii(TILE_RADIUS)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(12);
        // Keeps the description off the switch.
        copyParams.rightMargin = dp(16);
        copy.addView(text(titleValue, 14, text, false));
        copy.addView(text(detailValue, 11, secondary, false));
        row.addView(copy, copyParams);
        Switch toggle = new Switch(this);
        styleSwitch(toggle);
        toggle.setChecked(checked);
        toggle.setContentDescription(titleValue);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(42)));
        toggle.setTag(row);
        return toggle;
    }

    LinearLayout cardRow(int iconRes, String titleValue, String detailValue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(16), dp(14));
        row.setBackground(tile(surface, border, radii(TILE_RADIUS)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(14);
        copy.addView(text(titleValue, 15, text, true));
        copy.addView(text(detailValue, 12, secondary, false));
        row.addView(copy, copyParams);
        return row;
    }

    View settingRow(int iconRes, String labelValue, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackground(tile(surface, border, radii(TILE_RADIUS)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(labelValue, 14, text, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(12);
        row.addView(title, titleParams);
        row.addView(input, new LinearLayout.LayoutParams(dp(120), dp(46)));
        return row;
    }

    View paramsCard(String[][] rows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(surface, border, 1, TILE_RADIUS));
        for (int i = 0; i < rows.length; i++) {
            card.addView(paramRow(rows[i][0], rows[i][1]));
            if (i < rows.length - 1) addDivider(card, 0);
        }
        return card;
    }

    View paramRow(String labelValue, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.addView(text(labelValue, 13, secondary, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView valueView = text(value, 13, text, true);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    // A tonal button with an icon: the secondary action in a card or block
    // (copy, share, generate), next to the solid primaryButton.
    View tonalButton(int iconRes, String label, Runnable action) {
        return tonalButton(iconRes, label, accent, action);
    }

    // The red one, for removing something.
    View dangerButton(int iconRes, String label, Runnable action) {
        return tonalButton(iconRes, label, darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37), action);
    }

    View tonalButton(int iconRes, String label, int color, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setGravity(Gravity.CENTER);
        int fill = Color.rgb(
                (int) (Color.red(surface) + (Color.red(color) - Color.red(surface)) * 0.2f),
                (int) (Color.green(surface) + (Color.green(color) - Color.green(surface)) * 0.2f),
                (int) (Color.blue(surface) + (Color.blue(color) - Color.blue(surface)) * 0.2f));
        button.setBackground(tile(fill, Color.TRANSPARENT, radii(TILE_RADIUS)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.addView(icon(iconRes, color), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = text(label, 14, color, true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.leftMargin = dp(8);
        button.addView(text, textParams);
        button.setOnClickListener(v -> {
            bounce(v);
            action.run();
        });
        return button;
    }

    // A callout under a field: a tinted box with an icon and a short line.
    // setCallout paints it as a warning (yellow, triangle) or as fine
    // (green, check).
    LinearLayout callout() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(new ImageView(this), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView line = text("", 12, text, false);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(0, -2, 1f);
        lineParams.leftMargin = dp(12);
        box.addView(line, lineParams);
        return box;
    }

    void setCallout(LinearLayout box, boolean fine, String message) {
        int fg = fine ? (darkMode ? Color.rgb(129, 201, 149) : Color.rgb(24, 128, 56))
                : (darkMode ? Color.rgb(253, 214, 99) : Color.rgb(176, 96, 0));
        int bg = fine ? (darkMode ? Color.rgb(30, 46, 36) : Color.rgb(230, 245, 234))
                : (darkMode ? Color.rgb(56, 46, 20) : Color.rgb(255, 243, 224));
        box.setBackground(rounded(bg, Color.TRANSPARENT, 0, TILE_RADIUS));
        ImageView icon = (ImageView) box.getChildAt(0);
        icon.setImageResource(fine ? R.drawable.ic_check_circle : R.drawable.ic_warning);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        TextView line = (TextView) box.getChildAt(1);
        line.setText(message);
        line.setTextColor(fg);
        box.setContentDescription(message);
    }

    // A thin line across the page with a section's room above and below:
    // sets apart what does something else, like removing the profile.
    View pageDivider() {
        View line = new View(this);
        line.setBackgroundColor(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(SECTION_GAP);
        params.bottomMargin = dp(SECTION_GAP - TILE_GAP);
        line.setLayoutParams(params);
        return line;
    }

    LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(TILE_GAP);
        return params;
    }

    // ---- Text fields ----

    static final int FLOAT_ROOM = 8;

    // Height of a field wrapped by floating(): the 56dp box plus FLOAT_ROOM.
    static final int FIELD_HEIGHT = 56 + FLOAT_ROOM;

    EditText settingInput(String fieldHint, String value, int inputType) {
        EditText input = new EditText(this);
        input.setHint(fieldHint);
        input.setHintTextColor(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(text);
        input.setInputType(inputType);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(8), 0, dp(8), 0);
        return input;
    }

    // Wraps an outlined field (box holding input) in a Google-style floating
    // label: the label rests inside the box like a hint while the field is
    // empty and slides up onto the top border once it has text or focus.
    // The input's hint becomes the label; setFloatingLabel changes it later.
    View floating(View box, EditText input) {
        FrameLayout wrapper = new FrameLayout(this);
        // FLOAT_ROOM above the box holds the upper half of the floated label,
        // so it never pokes out under a neighbor.
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(-1, -1);
        boxParams.topMargin = dp(FLOAT_ROOM);
        wrapper.addView(box, boxParams);
        TextView label = text(String.valueOf(input.getHint()), 14, hint, false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(4), 0, dp(4), 0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wrapper.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        input.setContentDescription(input.getHint());
        input.setHint(null);
        input.setTag(label);
        // Top half over whatever is behind the field, bottom half over the
        // box: hides the border line behind the floated label.
        android.graphics.drawable.Drawable patch = new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                paint.setColor(colorBehind(wrapper));
                canvas.drawRect(b.left, b.top, b.right, b.exactCenterY(), paint);
                paint.setColor(surface);
                canvas.drawRect(b.left, b.exactCenterY(), b.right, b.bottom, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
        boolean[] floated = {false};
        boolean[] sliding = {false};
        Consumer<Boolean> place = animate -> {
            if (wrapper.getHeight() == 0) return;
            boolean up = input.hasFocus() || input.length() > 0;
            label.setTextColor(up && input.hasFocus() ? accent : up ? secondary : hint);
            // A layout pass (the keyboard opening) must not cut a slide short.
            if (sliding[0] && (!animate || up == floated[0])) return;
            // Text start of the input, relative to the wrapper.
            float x = input.getPaddingLeft() - dp(4);
            for (View v = input; v != wrapper && v != null; v = (View) v.getParent()) x += v.getLeft();
            label.setX(x);
            // Resting, the label must stop where the text does (before an eye
            // button); floated, it is scaled down and fits anyway.
            int textRight = (int) (x + dp(4) + input.getWidth() - input.getPaddingLeft() - input.getPaddingRight());
            label.setMaxWidth(Math.max(dp(40), textRight - (int) x + dp(4)));
            label.setPivotX(0);
            label.setPivotY(label.getHeight() / 2f);
            float room = dp(FLOAT_ROOM);
            float y = up ? room - label.getHeight() / 2f
                    : room + (wrapper.getHeight() - room - label.getHeight()) / 2f;
            float scale = up ? 0.8f : 1f;
            if (animate && up != floated[0]) {
                sliding[0] = true;
                // The patch only belongs on the border: drop it before sliding
                // down, add it once the label has landed up there.
                if (!up) label.setBackground(null);
                label.animate().y(y).scaleX(scale).scaleY(scale).setDuration(220)
                        .setInterpolator(EMPHASIZED)
                        .withEndAction(() -> {
                            sliding[0] = false;
                            label.setBackground(up ? patch : null);
                        }).start();
            } else {
                label.setY(y);
                label.setScaleX(scale);
                label.setScaleY(scale);
                label.setBackground(up ? patch : null);
            }
            floated[0] = up;
        };
        wrapper.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> place.accept(false)));
        input.setOnFocusChangeListener((v, focus) -> place.accept(true));
        bindText(input, v -> place.accept(true));
        return wrapper;
    }

    // The fill of the nearest ancestor that paints one, else the page's.
    int colorBehind(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            android.graphics.drawable.Drawable d = ((View) p).getBackground();
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) d).getColor();
            }
            if (d instanceof android.graphics.drawable.GradientDrawable
                    && ((android.graphics.drawable.GradientDrawable) d).getColor() != null) {
                return ((android.graphics.drawable.GradientDrawable) d).getColor().getDefaultColor();
            }
        }
        return background;
    }

    static void setFloatingLabel(EditText input, String value) {
        if (input == null || !(input.getTag() instanceof TextView)) return;
        ((TextView) input.getTag()).setText(value);
        input.setContentDescription(value);
    }

    void boxedInput(LinearLayout parent, EditText input, int topMargin) {
        FrameLayout field = new FrameLayout(this);
        field.setBackground(rounded(surface, border, 1, TILE_RADIUS));
        input.setPadding(dp(16), 0, dp(16), 0);
        field.addView(input, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        // FLOAT_ROOM for the label comes on top of topMargin, as in fieldParams.
        params.topMargin = topMargin;
        parent.addView(floating(field, input), params);
    }

    static void bindText(EditText input, Consumer<String> sink) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { sink.accept(s.toString().trim()); }
        });
    }

    // iconTextField lays out a leading icon and an EditText that fills the
    // rest of the row (with a reasonable gap between them, rather than
    // settingRow's separate caption + far-right fixed-width value box, which
    // doesn't read well when the "value" is itself the thing being typed),
    // plus an optional trailing action button (e.g. a show/hide toggle).
    View iconTextField(int iconRes, EditText input, ImageButton trailingButton) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, trailingButton != null ? dp(2) : dp(14), 0);
        row.setBackground(rounded(surface, border, 1, TILE_RADIUS));
        row.addView(icon(iconRes, secondary), new LinearLayout.LayoutParams(dp(20), dp(20)));
        input.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -1, 1f);
        inputParams.leftMargin = dp(12);
        row.addView(input, inputParams);
        if (trailingButton != null) {
            row.addView(trailingButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        return floating(row, input);
    }

    // ---- Motion and feedback ----

    // Material 3 "emphasized decelerate": quick start, long soft landing.
    static final android.view.animation.Interpolator EMPHASIZED =
            new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    final WeakHashMap<View, AnimatorSet> bounceAnimators = new WeakHashMap<>();
    Vibrator vibrator;

    // tap gives a light click haptic for a direct user interaction (button
    // press, toggle, list selection). Respects the system's haptic feedback
    // setting automatically and needs no permission.
    void tap(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // bounce gives a tap haptic plus a scale-down/scale-up feedback animation.
    // Repeated or rapid taps on the same view would otherwise stack multiple
    // overlapping ViewPropertyAnimator sequences (cancel() still runs a
    // pending withEndAction on API 23+), so any animation already running for
    // this exact view is fully cancelled and replaced before starting a new
    // one, and the scale is reset synchronously rather than relying on the
    // cancelled animation to leave it in a known state.
    void bounce(View view) {
        tap(view);
        AnimatorSet running = bounceAnimators.remove(view);
        if (running != null) running.cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        ObjectAnimator shrink = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f));
        shrink.setDuration(90);
        shrink.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator grow = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        // No overshoot: growing past 1 pushed views under their neighbors
        // and past the edges of clipping parents.
        grow.setDuration(220);
        grow.setInterpolator(EMPHASIZED);
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(shrink, grow);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                bounceAnimators.remove(view);
            }
        });
        bounceAnimators.put(view, set);
        set.start();
    }

    // vibrateSuccess/vibrateError are for state changes that aren't a direct
    // touch response (e.g. the tunnel finishing connecting a second later),
    // so they go through the Vibrator instead of View.performHapticFeedback.
    void vibrateSuccess() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    void vibrateError() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 45, 60, 45}, -1));
    }

    // Shows or hides view with a fade while everything below it slides to
    // its new place, instead of the neighbors jumping when it goes GONE.
    void setViewVisibleAnimated(View view, boolean visible) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
        if ((view.getVisibility() == View.VISIBLE) == visible) return;
        ViewGroup sceneRoot = transitionRoot(view);
        if (sceneRoot != null) {
            android.transition.TransitionSet transition = new android.transition.TransitionSet()
                    .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
                    .addTransition(new android.transition.Fade())
                    .addTransition(new android.transition.ChangeBounds());
            transition.setDuration(280);
            transition.setInterpolator(EMPHASIZED);
            // One transition per frame: later calls in the same toggle are
            // captured by the first.
            android.transition.TransitionManager.beginDelayedTransition(sceneRoot, transition);
        }
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // setInitialVisibility sets a view's starting visibility/alpha without
    // animating, for use when building a page (as opposed to
    // setViewVisibleAnimated, which is for reacting to a toggle afterwards).
    void setInitialVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        view.setAlpha(visible ? 1f : 0f);
    }

    void staggerIn(View view, int delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(320)
                .setInterpolator(EMPHASIZED).withLayer().start();
    }
}
