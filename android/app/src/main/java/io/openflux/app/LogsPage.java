package io.openflux.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The log screen: the filter (all / important / errors) and the colored,
// filtered view of MainActivity's log text.
final class LogsPage {
    private final MainActivity app;
    private static final int LOG_FILTER_ALL = 0;
    private static final int LOG_FILTER_IMPORTANT = 1;
    private static final int LOG_FILTER_ERRORS = 2;
    TextView logView;
    ScrollView logScroll;
    private int logFilter = LOG_FILTER_ALL;

    private static final Pattern LOG_TIMESTAMP_PATTERN =
            Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] ");

    private static final Pattern LOG_TAG_PATTERN = Pattern.compile(
            "^(?:\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] )?(\\[[A-Z0-9_-]+\\])");

    private static final Pattern ANDROID_LOG_ERROR_PATTERN = Pattern.compile(
            "(?i)\\b(?:failed|error)\\b|ошибк|не удалось");

    LogsPage(MainActivity app) {
        this.app = app;
    }

    View buildLogsPage() {
        LinearLayout page = app.page();
        LinearLayout header = new LinearLayout(app);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = app.text("Журнал событий", 25, app.text, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton clear = app.iconButton(R.drawable.ic_delete, "Очистить журнал");
        clear.setOnClickListener(v -> {
            app.tap(v);
            app.logs = "";
            renderLogs();
        });
        header.addView(clear, new LinearLayout.LayoutParams(app.dp(48), app.dp(48)));
        page.addView(header);

        TextView note = app.text("Логи хранятся только до закрытия приложения.", 11, app.secondary, false);
        LinearLayout.LayoutParams noteParams = app.matchWrap();
        noteParams.topMargin = app.dp(4);
        page.addView(note, noteParams);

        LinearLayout.LayoutParams filterParams = app.matchWrap();
        filterParams.topMargin = app.dp(16);
        page.addView(buildLogFilters(), filterParams);

        logView = app.text("", 13, app.logColor, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(app.dp(2), 1f);
        logView.setTextIsSelectable(true);
        logView.setPadding(app.dp(14), app.dp(12), app.dp(14), app.dp(12));
        logScroll = new ScrollView(app);
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        logParams.topMargin = app.dp(12);
        // Unlike pages that are fine fading behind the pill, log text must
        // stay fully readable - the card's own bottom edge needs to end
        // above the pill, not extend behind it with scroll-padding tricks.
        logParams.bottomMargin = app.navClearance();
        page.addView(logScroll, logParams);
        renderLogs();
        return page;
    }

    private View buildLogFilters() {
        String[] labels = {"Все", "Важные", "Ошибки"};
        LinearLayout bar = new LinearLayout(app);
        bar.setPadding(app.dp(4), app.dp(4), app.dp(4), app.dp(4));
        bar.setBackground(app.rounded(app.surface, app.border, 1, 14));
        TextView[] segments = new TextView[labels.length];
        Runnable paint = () -> {
            for (int i = 0; i < segments.length; i++) {
                boolean selected = logFilter == i;
                segments[i].setBackground(selected
                        ? app.rounded(app.tonal(0.18f), app.tonal(0.45f), 1, 11) : null);
                segments[i].setTextColor(selected ? app.accent : app.secondary);
                segments[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                segments[i].setSelected(selected);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            int filter = i;
            TextView segment = app.text(labels[i], 13, app.secondary, false);
            segment.setGravity(Gravity.CENTER);
            segment.setOnClickListener(v -> {
                if (logFilter == filter) return;
                app.tap(v);
                logFilter = filter;
                paint.run();
                renderLogs();
            });
            segments[i] = segment;
            bar.addView(segment, new LinearLayout.LayoutParams(0, app.dp(42), 1f));
        }
        paint.run();
        return bar;
    }

    void renderLogs() {
        if (logView == null) return;
        String visible = filteredLogs();
        logView.setTextColor(visible.isEmpty() ? app.secondary : app.logColor);
        logView.setText(visible.isEmpty()
                ? logFilter == LOG_FILTER_ERRORS ? "Ошибок пока нет."
                : logFilter == LOG_FILTER_IMPORTANT ? "Важных событий пока нет."
                : "Журнал пока пуст."
                : colorizeLogs(visible));
        if (app.autoScroll && logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String filteredLogs() {
        if (logFilter == LOG_FILTER_ALL || app.logs.isEmpty()) return app.logs;
        StringBuilder visible = new StringBuilder();
        String previousTimestamp = null;
        boolean previousIncluded = false;
        for (String line : app.logs.split("\\n", -1)) {
            Matcher timestamp = LOG_TIMESTAMP_PATTERN.matcher(line);
            String currentTimestamp = timestamp.find() ? line.substring(0, timestamp.end()) : null;
            Matcher tagMatch = LOG_TAG_PATTERN.matcher(line);
            String tag = tagMatch.find() ? tagMatch.group(1) : null;
            boolean sameEntry = tag == null && currentTimestamp != null
                    && currentTimestamp.equals(previousTimestamp);
            boolean include = sameEntry ? previousIncluded : logFilter == LOG_FILTER_ERRORS
                    ? isErrorLogLine(tag, line) : isImportantLogLine(tag);
            if (include && !line.isEmpty()) {
                if (visible.length() > 0) visible.append('\n');
                visible.append(line);
            }
            previousTimestamp = currentTimestamp;
            previousIncluded = include;
        }
        return visible.toString();
    }

    private boolean isErrorLogLine(String tag, String line) {
        if ("[ERROR]".equals(tag) || "[PANIC]".equals(tag)) return true;
        return ("[ANDROID]".equals(tag) || "[NODE]".equals(tag))
                && ANDROID_LOG_ERROR_PATTERN.matcher(line).find();
    }

    private boolean isImportantLogLine(String tag) {
        if (tag == null) return true;
        switch (tag) {
            case "[ANDROID]":
            case "[NODE]":
            case "[ERROR]":
            case "[PANIC]":
            case "[WARN]":
            case "[WARNING]":
            case "[SUCCESS]":
                return true;
            default:
                return false;
        }
    }

    private int logTagColor(String tag) {
        switch (tag) {
            case "[ERROR]":
            case "[PANIC]":
                return logErrorColor();
            case "[SUCCESS]":
                return logSuccessColor();
            case "[WARN]":
            case "[WARNING]":
                return logWarningColor();
            default:
                return app.accent;
        }
    }

    private int logErrorColor() {
        return app.darkMode ? Color.rgb(255, 145, 145) : Color.rgb(180, 38, 45);
    }

    private int logSuccessColor() {
        return app.darkMode ? Color.rgb(105, 211, 149) : Color.rgb(18, 122, 66);
    }

    private int logWarningColor() {
        return app.darkMode ? Color.rgb(255, 202, 97) : Color.rgb(151, 86, 0);
    }

    private int logMessageColor(String tag, String message) {
        switch (tag) {
            case "[ERROR]":
            case "[PANIC]":
                return logErrorColor();
            case "[SUCCESS]":
                return logSuccessColor();
            case "[WARN]":
            case "[WARNING]":
                return logWarningColor();
            case "[ANDROID]":
                return ANDROID_LOG_ERROR_PATTERN.matcher(message).find() ? logErrorColor() : app.logColor;
            case "[YDOCS]":
            case "[VOLGA]":
            case "[MAX]":
            case "[CUPS]":
            case "[M-DOCS]":
            case "[CRYPTO]":
            case "[SESSION]":
            case "[DEBUG]":
                return app.darkMode ? Color.rgb(165, 174, 193) : Color.rgb(91, 100, 117);
            default:
                return app.logColor;
        }
    }

    private CharSequence colorizeLogs(String rawLogs) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = rawLogs.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = builder.length();
            builder.append(line);
            Matcher timestamp = LOG_TIMESTAMP_PATTERN.matcher(line);
            if (timestamp.find()) {
                builder.setSpan(new ForegroundColorSpan(app.secondary),
                        start, start + timestamp.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            Matcher matcher = LOG_TAG_PATTERN.matcher(line);
            if (matcher.find()) {
                String tag = matcher.group(1);
                builder.setSpan(new ForegroundColorSpan(logTagColor(tag)),
                        start + matcher.start(1), start + matcher.end(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (matcher.end() < line.length()) {
                    builder.setSpan(new ForegroundColorSpan(logMessageColor(tag, line.substring(matcher.end()))),
                            start + matcher.end(), start + line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (i < lines.length - 1) builder.append("\n");
        }
        return builder;
    }
}
