package io.openflux.app;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// Settings -> Приложения: which apps use the tunnel (all, a whitelist or a
// blacklist), with search, system apps on request and the picked ones on
// top. The installed apps load once in the background and are cached.
final class AppPicker {
    private final MainActivity app;
    private List<AppEntry> installedAppsCache;
    private List<AppEntry> allInstalledAppsCache;
    boolean showSystemApps;
    private boolean allInstalledAppsLoading;
    private final List<Consumer<List<AppEntry>>> allInstalledAppsCallbacks = new ArrayList<>();
    private boolean installedAppsLoading;
    private int appsSettingsGeneration;
    private final List<Consumer<List<AppEntry>>> installedAppsCallbacks = new ArrayList<>();

    AppPicker(MainActivity app) {
        this.app = app;
    }

    View buildAppsSettings() {
        LinearLayout section = app.page();

        RecyclerView appListView = new RecyclerView(app);
        appListView.setLayoutManager(new LinearLayoutManager(app));
        appListView.setClipToPadding(false);
        appListView.setPadding(0, 0, 0, app.dp(8));
        appListView.setVerticalScrollBarEnabled(false);

        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(280);
        animator.setAddDuration(160);
        animator.setRemoveDuration(160);
        animator.setSupportsChangeAnimations(false);
        appListView.setItemAnimator(animator);

        LinearLayout header = new LinearLayout(app);
        header.setOrientation(LinearLayout.VERTICAL);

        header.addView(app.introText(
                "Выберите, какие приложения используют туннель. По умолчанию - все приложения, кроме OpenFlux. "
                        + "Действует только в режиме туннеля - в режиме прокси приложения подключаются к SOCKS5 сами."));

        int generation = ++appsSettingsGeneration;
        List<AppEntry> cachedApps = getInstalledAppsCache(showSystemApps);
        boolean loadingNeeded = cachedApps == null;
        View searchSpacer = new View(app);
        AppListAdapter appAdapter = new AppListAdapter(
                loadingNeeded ? Collections.emptyList() : cachedApps, header,
                !AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode), searchSpacer);

        LinearLayout searchRow = new LinearLayout(app);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        // A pill, like the settings search.
        searchRow.setBackground(app.rounded(app.surface, app.border, 1, 24));
        EditText searchInput = app.settingInput("Поиск по названию или пакету", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setSingleLine(true);
        searchInput.setContentDescription("Поиск приложений по названию или имени пакета");
        searchInput.setPadding(app.dp(10), 0, 0, 0);
        LinearLayout.LayoutParams searchIconParams = new LinearLayout.LayoutParams(app.dp(20), app.dp(20));
        searchIconParams.leftMargin = app.dp(14);
        searchRow.addView(app.icon(R.drawable.ic_search, app.hint), searchIconParams);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageButton clearSearch = app.iconButton(R.drawable.ic_close,
                "Очистить поиск");
        clearSearch.setVisibility(View.INVISIBLE);
        clearSearch.setOnClickListener(v -> {
            app.tap(v);
            searchInput.setText("");
        });
        searchRow.addView(clearSearch, new LinearLayout.LayoutParams(app.dp(48), -1));

        TextView emptyResults = app.text("Ничего не найдено", 12, app.secondary, false);
        emptyResults.setVisibility(View.GONE);
        Runnable updateSearchSpacer = () -> {
            boolean searching = !AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode)
                    && ((app.keyboardOpen && searchInput.hasFocus())
                    || !searchInput.getText().toString().trim().isEmpty());
            int height = searching ? Math.max(0, appListView.getHeight()
                    - appListView.getPaddingTop() - appListView.getPaddingBottom()
                    - searchRow.getHeight() - header.getPaddingBottom()) : 0;
            appAdapter.setSearchSpacerHeight(height);
        };
        Runnable updateSearchState = () -> {
            clearSearch.setVisibility(searchInput.length() == 0 ? View.INVISIBLE : View.VISIBLE);
            boolean noResults = !AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode)
                    && getInstalledAppsCache(showSystemApps) != null
                    && !searchInput.getText().toString().trim().isEmpty()
                    && appAdapter.getVisibleAppsCount() == 0;
            emptyResults.setVisibility(noResults ? View.VISIBLE : View.GONE);
            updateSearchSpacer.run();
        };
        ThemedActivity.bindText(searchInput, query -> {
            appAdapter.setSearchQuery(query);
            updateSearchState.run();
        });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) app.getSystemService(app.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            searchInput.clearFocus();
            return true;
        });
        searchRow.setVisibility(AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode) ? View.GONE : View.VISIBLE);

        Runnable alignSearch = () -> {
            if (!app.keyboardOpen || !searchInput.hasFocus() || !appListView.isAttachedToWindow()
                    || searchRow.getVisibility() != View.VISIBLE || searchRow.getHeight() == 0) return;
            // The search field is nested in the header; measure its current top in list coordinates.
            int distance = header.getTop() + searchRow.getTop()
                    - appListView.getPaddingTop() - app.dp(6);
            if (Math.abs(distance) <= app.dp(1)) return;
            appListView.smoothScrollBy(0, distance,
                    new android.view.animation.AccelerateDecelerateInterpolator(), 300);
        };
        Runnable requestSearchAlignment = () -> {
            appListView.removeCallbacks(alignSearch);
            // Run after layout, when the keyboard's insets and header measurements are applied.
            appListView.post(alignSearch);
        };
        searchInput.setOnFocusChangeListener((view, focused) -> {
            updateSearchSpacer.run();
            if (focused) requestSearchAlignment.run();
            else appListView.removeCallbacks(alignSearch);
        });

        appListView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop
                    && right - left == oldRight - oldLeft) return;

            // Update after layout, outside RecyclerView's layout calculation.
            appListView.post(() -> {
                if (!appListView.isAttachedToWindow()) return;
                updateSearchSpacer.run();
                // The keyboard opens after the field takes focus: align once it has.
                if (searchInput.hasFocus()) requestSearchAlignment.run();
            });
        });

        String[] filterModes = {
                AppFilter.MODE_OFF,
                AppFilter.MODE_WHITELIST,
                AppFilter.MODE_BLACKLIST
        };

        // Like the search, the system-apps switch only matters with a list to pick from.
        View[] systemAppsHolder = new View[1];
        View filterChoice = app.choiceList(new String[][]{
                        {"Все приложения"},
                        {"Только выбранные", "Белый список"},
                        {"Все, кроме выбранных", "Чёрный список"},
                }, null,
                Math.max(0,
                        java.util.Arrays.asList(filterModes)
                                .indexOf(app.settingsPage.editorAppFilterMode)),
                i -> {
                    app.settingsPage.editorAppFilterMode = filterModes[i];
                    appAdapter.setAppsVisible(
                            !AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode));
                    boolean visible = !AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode);
                    if (systemAppsHolder[0] != null) app.setViewVisibleAnimated(systemAppsHolder[0], visible);
                    searchRow.animate().cancel();

                    if (visible) {
                        searchRow.setAlpha(1f);
                        searchRow.setVisibility(View.VISIBLE);
                    } else {
                        RecyclerView.ItemAnimator listAnimator  = appListView.getItemAnimator();
                        long duration = listAnimator  != null ? listAnimator.getRemoveDuration() : 0L;

                        searchRow.animate()
                                .alpha(0f)
                                .setDuration(duration)
                                .withEndAction(() -> {
                                    if (AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode)) {
                                        searchRow.setVisibility(View.GONE);
                                    }
                                })
                                .start();
                    }
                    if (!visible) {
                        android.view.inputmethod.InputMethodManager keyboard =
                                (android.view.inputmethod.InputMethodManager) app.getSystemService(app.INPUT_METHOD_SERVICE);
                        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                        searchInput.clearFocus();
                    }
                    updateSearchState.run();
                });

        header.addView(filterChoice, app.tileParams());

        Switch systemAppsSwitch = app.settingSwitch(R.drawable.ic_apps,
                "Показать системные приложения",
                "Включая приложения без значка в меню", showSystemApps);
        View systemAppsRow = (View) systemAppsSwitch.getTag();
        systemAppsHolder[0] = systemAppsRow;
        systemAppsRow.setVisibility(AppFilter.MODE_OFF.equals(app.settingsPage.editorAppFilterMode) ? View.GONE : View.VISIBLE);
        // The app list is a block of its own under the filter choice.
        LinearLayout.LayoutParams systemAppsParams = app.matchWrap();
        systemAppsParams.topMargin = app.dp(ThemedActivity.SECTION_GAP);
        header.addView(systemAppsRow, systemAppsParams);

        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, app.dp(48));
        searchParams.topMargin = app.dp(ThemedActivity.TILE_GAP);
        header.addView(searchRow, searchParams);
        header.addView(emptyResults, app.tileParams());

        TextView loadStatus = app.text("", 12, app.secondary, false);
        loadStatus.setVisibility(View.GONE);
        header.addView(loadStatus, app.tileParams());

        Runnable[] requestApps = new Runnable[1];
        int[] loadRequest = {0};
        requestApps[0] = () -> {
            int requestId = ++loadRequest[0];
            boolean includeSystem = showSystemApps;
            emptyResults.setVisibility(View.GONE);
            loadStatus.setVisibility(View.GONE);
            loadStatus.setOnClickListener(null);
            loadInstalledAppsAsync(includeSystem, loaded -> {
                // A previous screen must not update the current settings draft.
                if (requestId != loadRequest[0] || generation != appsSettingsGeneration
                        || app.currentPage != MainActivity.PAGE_SETTINGS
                        || !app.settingsPage.settingsDetailOpen || app.settingsPage.settingsSubTab != SettingsPage.SETTINGS_APPS) return;

                if (loaded == null) {
                    loadStatus.setText("Не удалось загрузить приложения. Нажмите, чтобы повторить.");
                    loadStatus.setVisibility(View.VISIBLE);
                    loadStatus.setOnClickListener(v -> {
                        app.tap(v);
                        requestApps[0].run();
                    });
                    return;
                }

                loadStatus.setVisibility(View.GONE);
                appAdapter.setLoadedApps(loaded);
                updateSearchState.run();
            });
        };

        systemAppsSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            showSystemApps = checked;
            // This is a display preference; VPN app selections are saved separately.
            app.appFilterPrefs.edit().putBoolean("show_system_apps", checked).apply();
            requestApps[0].run();
        });
        systemAppsRow.setOnClickListener(v ->
                systemAppsSwitch.setChecked(!systemAppsSwitch.isChecked()));

        appListView.setAdapter(appAdapter);
        section.addView(appListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (loadingNeeded) requestApps[0].run();
        return section;
    }

    private List<AppEntry> getInstalledAppsCache(boolean includeSystem) {
        return includeSystem ? allInstalledAppsCache : installedAppsCache;
    }

    private void loadInstalledAppsAsync(boolean includeSystem, Consumer<List<AppEntry>> onLoaded) {
        // Cache, callbacks and adapter updates are accessed only on the UI thread.
        List<AppEntry> cached = getInstalledAppsCache(includeSystem);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        List<Consumer<List<AppEntry>>> pending = includeSystem
                ? allInstalledAppsCallbacks : installedAppsCallbacks;
        pending.add(onLoaded);
        if (includeSystem ? allInstalledAppsLoading : installedAppsLoading) return;
        if (includeSystem) allInstalledAppsLoading = true;
        else installedAppsLoading = true;

        new Thread(() -> {
            List<AppEntry> result;
            try {
                // Package queries, labels and icons stay off the UI thread.
                result = loadInstalledApps(includeSystem);
            } catch (RuntimeException error) {
                android.util.Log.w("OpenFlux", "Failed to load installed apps", error);
                result = null;
            }
            List<AppEntry> loaded = result;
            app.handler.post(() -> {
                if (includeSystem) {
                    allInstalledAppsLoading = false;
                    if (loaded != null) allInstalledAppsCache = loaded;
                } else {
                    installedAppsLoading = false;
                    if (loaded != null) installedAppsCache = loaded;
                }
                List<Consumer<List<AppEntry>>> callbacks = new ArrayList<>(pending);
                pending.clear();
                if (app.isFinishing() || app.isDestroyed()) return;
                for (Consumer<List<AppEntry>> callback : callbacks) callback.accept(loaded);
            });
        }, includeSystem ? "load-all-installed-apps" : "load-launcher-apps").start();
    }

    private List<AppEntry> loadInstalledApps(boolean includeSystem) {
        PackageManager packageManager = app.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        LinkedHashMap<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(app.getPackageName()) || byPackage.containsKey(packageName)) continue;
            String label = info.loadLabel(packageManager).toString();
            Drawable icon = info.loadIcon(packageManager);
            byPackage.put(packageName, new AppEntry(packageName, label, icon));
        }
        if (includeSystem) {
            // Include packages without launcher activities, such as system services.
            // Existing launcher names and icons remain consistent between both lists.
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                String packageName = info.packageName;
                if (packageName.equals(app.getPackageName()) || byPackage.containsKey(packageName)) continue;
                String label = info.loadLabel(packageManager).toString();
                Drawable icon = info.loadIcon(packageManager);
                byPackage.put(packageName, new AppEntry(packageName, label, icon));
            }
        }
        return new ArrayList<>(byPackage.values());
    }

    // An app in the picker: a selection tile like choiceList's rows, with a
    // round mark (an empty ring until picked, since several can be).
    private View buildAppRow() {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(app.dp(60));
        row.setPadding(app.dp(14), app.dp(10), app.dp(16), app.dp(10));
        ImageView icon = new ImageView(app);
        row.addView(icon, new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout labels = new LinearLayout(app);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = app.text("", 15, app.text, true);
        labels.addView(labelView, new LinearLayout.LayoutParams(-1, -2));
        TextView packageView = app.text("", 12, app.secondary, false);
        labels.addView(packageView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelParams.leftMargin = app.dp(14);
        labelParams.rightMargin = app.dp(12);
        row.addView(labels, labelParams);
        ImageView mark = new ImageView(app);
        mark.setPadding(app.dp(3), app.dp(3), app.dp(3), app.dp(3));
        row.addView(mark, new LinearLayout.LayoutParams(app.dp(24), app.dp(24)));
        return row;
    }

    // Paints an app row as a separate tile, like choiceList's, and whether
    // it is picked.
    private void paintAppRow(View row, AppRowHolder holder, int index, int count, boolean on) {
        row.setBackground(app.tile(on ? app.tonal(0.18f) : app.surface, on ? app.tonal(0.45f) : app.border,
                app.radii(ThemedActivity.TILE_RADIUS)));
        holder.label.setTextColor(on ? app.accent : app.text);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        if (on) dot.setColor(app.accent);
        else dot.setStroke(app.dp(2), app.hint);
        holder.mark.setBackground(dot);
        holder.mark.setImageResource(on ? R.drawable.ic_check : 0);
        holder.mark.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) row.getLayoutParams();
        int gap = app.dp(ThemedActivity.TILE_GAP);
        if (params != null && params.topMargin != gap) {
            params.topMargin = gap;
            row.setLayoutParams(params);
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class AppRowHolder {
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final ImageView mark;

        AppRowHolder(View row) {
            LinearLayout layout = (LinearLayout) row;
            icon = (ImageView) layout.getChildAt(0);
            LinearLayout labels = (LinearLayout) layout.getChildAt(1);
            label = (TextView) labels.getChildAt(0);
            packageName = (TextView) labels.getChildAt(1);
            mark = (ImageView) layout.getChildAt(2);
        }
    }

    private static final class AppPickerViewHolder extends RecyclerView.ViewHolder {
        final AppRowHolder appRow;

        AppPickerViewHolder(View view, boolean isAppRow) {
            super(view);
            appRow = isAppRow ? new AppRowHolder(view) : null;
        }
    }

    private final class AppListAdapter extends RecyclerView.Adapter<AppPickerViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_APP = 1;
        private static final int TYPE_SPACER = 2;
        private static final String PAINT = "paint";

        private final List<AppEntry> apps;
        private final List<AppEntry> visibleApps = new ArrayList<>();
        private String searchQuery = "";
        private final LinearLayout header;
        private final View searchSpacer;
        private int searchSpacerHeight;
        private final LinkedHashMap<String, Long> itemIds = new LinkedHashMap<>();
        private boolean appsVisible;

        AppListAdapter(List<AppEntry> apps, LinearLayout header, boolean appsVisible, View searchSpacer) {
            // Keep the installed-app cache independent of this screen's ordering.
            this.apps = new ArrayList<>(apps);
            this.header = header;
            this.searchSpacer = searchSpacer;
            this.appsVisible = appsVisible;
            header.setPadding(0, 0, 0, app.dp(14));

            assignItemIds();
            setHasStableIds(true);
            sortApps();
            rebuildVisibleApps();
        }

        void setSearchSpacerHeight(int height) {
            height = Math.max(0, height);
            if (searchSpacerHeight == height) return;
            searchSpacerHeight = height;
            // Keep enough scroll range for a short result list without adding fake applications.
            ViewGroup.LayoutParams params = searchSpacer.getLayoutParams();
            if (params != null) {
                params.height = height;
                searchSpacer.setLayoutParams(params);
            }
        }

        private void assignItemIds() {
            // Keep package IDs stable even if the backing list is replaced.
            for (AppEntry entry : apps) {
                if (!itemIds.containsKey(entry.packageName)) {
                    itemIds.put(entry.packageName, (long) itemIds.size() + 1);
                }
            }
        }

        void setLoadedApps(List<AppEntry> loaded) {
            List<AppEntry> previous = new ArrayList<>(visibleApps);
            apps.clear();
            apps.addAll(loaded);
            assignItemIds();
            sortApps();
            rebuildVisibleApps();
            if (appsVisible) dispatchAppDiff(previous);
        }

        void setSearchQuery(String query) {
            String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
            if (searchQuery.equals(normalized)) return;
            List<AppEntry> previous = new ArrayList<>(visibleApps);
            searchQuery = normalized;
            rebuildVisibleApps();
            if (appsVisible) dispatchAppDiff(previous);
        }

        int getVisibleAppsCount() {
            return visibleApps.size();
        }

        private void rebuildVisibleApps() {
            // Filtering changes only the displayed list, never the selected package set.
            visibleApps.clear();
            for (AppEntry entry : apps) {
                if (searchQuery.isEmpty()
                        || entry.label.toLowerCase(java.util.Locale.ROOT).contains(searchQuery)
                        || entry.packageName.toLowerCase(java.util.Locale.ROOT).contains(searchQuery)) {
                    visibleApps.add(entry);
                }
            }
        }

        private void dispatchAppDiff(List<AppEntry> previous) {
            List<AppEntry> current = new ArrayList<>(visibleApps);
            // Keep the header and spacer identities stable while application results change.
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return previous.size() + 2; }
                @Override public int getNewListSize() { return current.size() + 2; }

                @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    if (oldPosition == 0 || newPosition == 0) return oldPosition == newPosition;
                    boolean oldSpacer = oldPosition == previous.size() + 1;
                    boolean newSpacer = newPosition == current.size() + 1;
                    if (oldSpacer || newSpacer) return oldSpacer && newSpacer;
                    return previous.get(oldPosition - 1).packageName
                            .equals(current.get(newPosition - 1).packageName);
                }

                @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    if (oldPosition == 0 || newPosition == 0) return oldPosition == newPosition;
                    boolean oldSpacer = oldPosition == previous.size() + 1;
                    boolean newSpacer = newPosition == current.size() + 1;
                    if (oldSpacer || newSpacer) return oldSpacer && newSpacer;
                    AppEntry oldEntry = previous.get(oldPosition - 1);
                    AppEntry newEntry = current.get(newPosition - 1);
                    return oldEntry.label.equals(newEntry.label) && oldEntry.icon == newEntry.icon;
                }
            }).dispatchUpdatesTo(this);
        }

        private void sortApps() {
            java.text.Collator collator = java.text.Collator.getInstance();
            collator.setStrength(java.text.Collator.SECONDARY);

            apps.sort((a, b) -> {
                boolean aSelected = app.settingsPage.editorSelectedApps.contains(a.packageName);
                boolean bSelected = app.settingsPage.editorSelectedApps.contains(b.packageName);
                if (aSelected != bSelected) return aSelected ? -1 : 1;

                int byLabel = collator.compare(a.label, b.label);
                return byLabel != 0
                        ? byLabel
                        : a.packageName.compareTo(b.packageName);
            });
        }

        void setAppsVisible(boolean visible) {
            if (appsVisible == visible) return;
            appsVisible = visible;
            header.setPadding(0, 0, 0, app.dp(14));

            // The header always remains at position zero.
            int count = visibleApps.size() + 1;
            if (visible) notifyItemRangeInserted(1, count);
            else notifyItemRangeRemoved(1, count);
        }

        @Override public int getItemCount() {
            return 1 + (appsVisible ? visibleApps.size() + 1 : 0);
        }

        @Override public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            return position == visibleApps.size() + 1 ? TYPE_SPACER : TYPE_APP;
        }

        @Override public long getItemId(int position) {
            if (position == 0) return 0L;
            if (position == visibleApps.size() + 1) return Long.MAX_VALUE;
            return itemIds.get(visibleApps.get(position - 1).packageName);
        }

        @Override public AppPickerViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = viewType == TYPE_HEADER ? header
                    : viewType == TYPE_SPACER ? searchSpacer : buildAppRow();
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    viewType == TYPE_SPACER ? searchSpacerHeight : ViewGroup.LayoutParams.WRAP_CONTENT));
            return new AppPickerViewHolder(view, viewType == TYPE_APP);
        }

        @Override public void onBindViewHolder(AppPickerViewHolder viewHolder, int position) {
            if (viewHolder.appRow == null) return;

            AppEntry entry = visibleApps.get(position - 1);
            AppRowHolder holder = viewHolder.appRow;
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName);
            paintAppRow(viewHolder.itemView, holder, position - 1, visibleApps.size(),
                    app.settingsPage.editorSelectedApps.contains(entry.packageName));
            viewHolder.itemView.setOnClickListener(v -> {
                if (viewHolder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                app.tap(v);
                updateSelection(entry, !app.settingsPage.editorSelectedApps.contains(entry.packageName));
            });
        }

        // A pick changes a row's look and, once it moves, which rows end the
        // group: repaint the rows without rebinding them.
        @Override public void onBindViewHolder(AppPickerViewHolder viewHolder, int position, List<Object> payloads) {
            if (!payloads.contains(PAINT) || viewHolder.appRow == null) {
                super.onBindViewHolder(viewHolder, position, payloads);
                return;
            }
            AppEntry entry = visibleApps.get(position - 1);
            paintAppRow(viewHolder.itemView, viewHolder.appRow, position - 1, visibleApps.size(),
                    app.settingsPage.editorSelectedApps.contains(entry.packageName));
        }

        private void updateSelection(AppEntry entry, boolean checked) {
            int oldPosition = findAppPosition(entry.packageName);
            if (oldPosition < 0) return;

            boolean changed = checked
                    ? app.settingsPage.editorSelectedApps.add(entry.packageName)
                    : app.settingsPage.editorSelectedApps.remove(entry.packageName);
            if (!changed) return;

            sortApps();
            rebuildVisibleApps();
            int newPosition = findAppPosition(entry.packageName);
            if (appsVisible && oldPosition != newPosition) {
                // Only this package moved; RecyclerView animates affected rows.
                // Offset by one because the header is also an adapter item.
                notifyItemMoved(oldPosition + 1, newPosition + 1);
            }
            if (appsVisible) notifyItemRangeChanged(1, visibleApps.size(), PAINT);
        }

        private int findAppPosition(String packageName) {
            // Bound rows may still reference entries from the previous cached list.
            for (int i = 0; i < visibleApps.size(); i++) {
                if (visibleApps.get(i).packageName.equals(packageName)) return i;
            }
            return -1;
        }

        @Override public void onViewRecycled(AppPickerViewHolder viewHolder) {
            if (viewHolder.appRow != null) viewHolder.itemView.setOnClickListener(null);
            super.onViewRecycled(viewHolder);
        }
    }
}
