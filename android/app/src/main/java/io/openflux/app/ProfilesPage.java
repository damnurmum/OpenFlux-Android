package io.openflux.app;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

// The Profiles tab: the list of saved profiles, the "+" and QR menus, and
// the profile dropdown Home opens; the editor itself is ProfileEditor.
final class ProfilesPage {
    private final MainActivity app;
    private PopupWindow profileDropdown;

    ProfilesPage(MainActivity app) {
        this.app = app;
    }

    void showProfileDropdown(View anchor) {
        if (app.profiles.isEmpty()) {
            app.showPage(MainActivity.PAGE_PROFILES);
            return;
        }
        LinearLayout content = new LinearLayout(app);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(app.dp(4), app.dp(4), app.dp(4), app.dp(4));
        View selectedRow = null;
        for (Profile p : app.profiles) {
            boolean selected = p.id == app.selectedProfileId;
            LinearLayout row = new LinearLayout(app);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(app.dp(12), app.dp(10), app.dp(12), app.dp(10));
            row.setBackground(app.ripple(Color.TRANSPARENT, 8));
            row.setClickable(true);
            row.addView(app.iconBubble(app.profileTransportIcon(p), selected),
                    new LinearLayout.LayoutParams(app.dp(34), app.dp(34)));
            TextView nameView = app.text(p.name, 14, app.text, selected);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
            nameParams.leftMargin = app.dp(12);
            nameParams.rightMargin = app.dp(8);
            row.addView(nameView, nameParams);
            if (selected) row.addView(app.icon(R.drawable.ic_check, app.accent), new LinearLayout.LayoutParams(app.dp(18), app.dp(18)));
            row.setOnClickListener(v -> {
                app.tap(v);
                app.selectProfile(p.id);
                if (profileDropdown != null) profileDropdown.dismiss();
            });
            content.addView(row, new LinearLayout.LayoutParams(-1, app.dp(56)));
            if (selected) selectedRow = row;
        }
        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        scroll.setClipToOutline(true);
        scroll.addView(content);
        int popupWidth = anchor.getWidth();
        content.measure(View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupHeight = Math.min(content.getMeasuredHeight(), app.dp(176));
        PopupWindow popup = new PopupWindow(scroll, popupWidth, popupHeight, true);
        popup.setElevation(app.dp(8));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        profileDropdown = popup;
        popup.setOnDismissListener(() -> profileDropdown = null);
        popup.showAsDropDown(anchor, 0, app.dp(4));
        View focusRow = selectedRow;
        if (focusRow != null) scroll.post(() -> scroll.scrollTo(0, Math.max(0, focusRow.getTop() - app.dp(16))));
    }

    private void showProfileActionMenu(View anchor) {
        showActionMenu(anchor,
                new String[]{"Добавить профиль", "Установить ноду на VPS"},
                new String[]{"Настроить транспорт и подключение", "Создать канал на своём сервере"},
                new int[]{R.drawable.ic_add, R.drawable.ic_terminal},
                new Runnable[]{() -> app.profileEditor.openProfileEditor(null), app::openNodeWizard});
    }

    private void showQrActionMenu(View anchor) {
        showActionMenu(anchor,
                new String[]{"Сканировать камерой", "Выбрать фото из галереи"},
                new String[]{"Наведите камеру на QR-код", "Прочитать QR-код на снимке"},
                new int[]{R.drawable.ic_qr_scan, R.drawable.ic_image},
                new Runnable[]{app.shareLinks::scanShareQr, app.shareLinks::pickShareQrImage});
    }

    private void showActionMenu(View anchor, String[] titles, String[] details, int[] icons, Runnable[] actions) {
        LinearLayout menu = new LinearLayout(app);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(app.dp(5), app.dp(5), app.dp(5), app.dp(5));
        menu.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        PopupWindow popup = new PopupWindow(menu, app.dp(280), -2, true);
        popup.setElevation(app.dp(8));
        popup.setOutsideTouchable(true);
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(app);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(app.dp(10), app.dp(10), app.dp(10), app.dp(10));
            row.setBackground(app.ripple(Color.TRANSPARENT, 8));
            row.addView(app.iconBubble(icons[i]), new LinearLayout.LayoutParams(app.dp(38), app.dp(38)));
            LinearLayout copy = new LinearLayout(app);
            copy.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
            copyParams.leftMargin = app.dp(12);
            copy.addView(app.text(titles[i], 14, app.text, true));
            copy.addView(app.text(details[i], 11, app.secondary, false));
            row.addView(copy, copyParams);
            row.setOnClickListener(v -> {
                app.tap(v);
                popup.dismiss();
                actions[index].run();
            });
            menu.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - app.dp(280), app.dp(4));
    }

    View buildProfilesPage() {
        LinearLayout page = app.page();
        if (app.nodeWizard != null) {
            page.addView(app.nodeWizard.header());
            LinearLayout.LayoutParams wizardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            wizardParams.topMargin = app.dp(16);
            page.addView(app.nodeWizard.content(), wizardParams);
            return page;
        }
        if (app.profileEditorOpen) {
            page.addView(app.profileEditor.buildProfileEditorHeader());
            LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            editorParams.topMargin = app.dp(16);
            View editor = app.wrapScroll(app.profileEditor.buildProfileEditor());
            // The save button is pinned below instead, above the nav pill.
            ThemedActivity.scrollBottom(editor, app.dp(8));
            page.addView(editor, editorParams);
            Button save = app.primaryButton("Сохранить профиль", () -> app.profileEditor.saveProfileFromEditor(
                    app.profileEditor.profileNameInput.getText().toString().trim(),
                    app.profileEditor.urlInput.getText().toString().trim(),
                    app.profileEditor.encryptionInput.getText().toString().trim(),
                    app.profileEditor.maxTokenInput.getText().toString().trim(),
                    app.profileEditor.maxUidInput.getText().toString().trim()));
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, app.dp(52));
            saveParams.topMargin = app.dp(10);
            saveParams.bottomMargin = app.navClearance();
            page.addView(save, saveParams);
            app.pinnedSave = save;
            if (app.keyboardOpen) save.setVisibility(View.GONE);
            return page;
        }

        LinearLayout header = new LinearLayout(app);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(app);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(app.text("Профили", 25, app.text, true));
        titles.addView(app.text("Наборы параметров для разных серверов", 12, app.secondary, false), app.matchWrap());
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton scanButton = app.iconButton(R.drawable.ic_qr_scan, "Загрузить QR-код");
        scanButton.setOnClickListener(v -> {
            app.tap(v);
            showQrActionMenu(v);
        });
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(app.dp(44), app.dp(44));
        scanParams.rightMargin = app.dp(8);
        header.addView(scanButton, scanParams);
        ImageButton addButton = app.iconButton(R.drawable.ic_add, "Добавить профиль");
        addButton.setOnClickListener(v -> {
            app.tap(v);
            showProfileActionMenu(v);
        });
        header.addView(addButton, new LinearLayout.LayoutParams(app.dp(44), app.dp(44)));
        page.addView(header);

        if (app.profiles.isEmpty()) {
            TextView empty = app.text("Пока нет ни одного профиля. Нажмите + и добавьте первый.", 13, app.secondary, false);
            LinearLayout.LayoutParams emptyParams = app.matchWrap();
            emptyParams.topMargin = app.dp(24);
            page.addView(empty, emptyParams);
            return app.wrapScroll(page);
        }

        LinearLayout list = new LinearLayout(app);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        for (int i = 0; i < app.profiles.size(); i++) {
            list.addView(buildProfileListRow(app.profiles.get(i)));
            if (i < app.profiles.size() - 1) app.addDivider(list, 0);
        }
        LinearLayout.LayoutParams listParams = app.matchWrap();
        listParams.topMargin = app.dp(20);
        page.addView(list, listParams);
        return app.wrapScroll(page);
    }

    private View buildProfileListRow(Profile p) {
        boolean selected = p.id == app.selectedProfileId;
        LinearLayout row = new LinearLayout(app);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(app.dp(16), app.dp(10), app.dp(6), app.dp(10));
        row.setBackground(app.ripple(Color.TRANSPARENT, 0));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(app.iconBubble(app.profileTransportIcon(p), selected),
                new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout copy = new LinearLayout(app);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = app.dp(16);
        copy.addView(app.text(p.name, 15, app.text, selected));
        copy.addView(app.text(app.profileTransportSummary(p) + (selected ? " · активен" : ""), 12, app.secondary, false));
        row.addView(copy, copyParams);
        ImageButton editButton = app.iconButton(R.drawable.ic_settings, "Изменить профиль «" + p.name + "»");
        editButton.setOnClickListener(v -> {
            app.tap(v);
            app.profileEditor.openProfileEditor(p);
        });
        row.addView(editButton, new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        ImageButton deleteButton = app.iconButton(R.drawable.ic_delete, "Удалить профиль «" + p.name + "»");
        deleteButton.setOnClickListener(v -> {
            app.tap(v);
            app.deleteProfile(p);
        });
        row.addView(deleteButton, new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        row.setOnClickListener(v -> {
            app.bounce(v);
            app.selectProfile(p.id);
        });
        return row;
    }
}
