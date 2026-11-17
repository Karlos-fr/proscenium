package fr.proscenium.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Panneau unique pour créer ou modifier un raccourci HDMI : entrée (← →), nom (clavier),
 * icône (grille des consoles), puis Enregistrer / Annuler (et Retirer en modification).
 * Il s'affiche dans un panneau de verre de l'accueil, le même que celui des réglages
 * (verre réfractif, verre fumé, contour), et non dans une fenêtre à part.
 *
 * La grille gère elle-même ses deux états, sans les « sélecteurs » d'Android :
 * l'icône <b>choisie</b> porte un liseré de la couleur d'accent et une coche,
 * la case <b>survolée</b> à la télécommande grandit et porte un liseré blanc, dessiné
 * par-dessus l'image, dans ses limites.
 */
final class ShortcutEditor {
    interface Listener {
        void saved(InputShortcuts.Shortcut sc);
        void removed();
        /** Le panneau s'est refermé (enregistré, retiré ou annulé). */
        void closed();
    }

    private static final int COLUMNS = 5;

    private final Context ctx;
    private final float density;
    private final int accent;
    private final GlassView host;
    private final Listener listener;
    private boolean open = true;
    private final InputShortcuts.Shortcut sc;
    private final List<String[]> inputs;
    private final List<FrameLayout> cells = new ArrayList<>();
    private int inputIndex;
    private String icon;
    /** Le nom a été tapé à la main : choisir une icône ne le remplace plus. */
    private boolean nameTyped;
    private boolean settingName;
    private TextView inputValue;
    private EditText nameField;

    /** {@code existing} null : nouveau raccourci. */
    static ShortcutEditor show(Context ctx, GlassView host, int accent, InputShortcuts.Shortcut existing,
                               Listener listener) {
        return new ShortcutEditor(ctx, host, accent, existing, listener);
    }

    private ShortcutEditor(Context ctx, GlassView host, int accent, InputShortcuts.Shortcut existing,
                           Listener listener) {
        this.ctx = ctx;
        this.host = host;
        this.listener = listener;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.accent = accent;
        this.inputs = InputShortcuts.hdmiInputs(ctx);
        boolean creating = existing == null;
        if (creating) {
            sc = new InputShortcuts.Shortcut();
            sc.input = inputs.isEmpty() ? "" : inputs.get(0)[0];
            sc.icon = "";
            sc.name = inputs.isEmpty() ? "HDMI" : inputs.get(0)[1];
        } else {
            sc = new InputShortcuts.Shortcut();
            sc.id = existing.id;
            sc.input = existing.input;
            sc.name = existing.name;
            sc.icon = existing.icon;
        }
        icon = sc.icon;
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i)[0].equals(sc.input)) inputIndex = i;
        // Un nom qui n'est ni celui d'une console ni celui d'une entrée a été choisi : on le garde
        nameTyped = !creating && InputShortcuts.iconName(sc.icon) == null && !isInputLabel(sc.name)
                || !creating && InputShortcuts.iconName(sc.icon) != null && !sc.name.equals(InputShortcuts.iconName(sc.icon));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(22), dp(26), dp(16));

        // Titre comme celui des réglages
        TextView title = text(24, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(8));
        title.setText(creating ? R.string.shortcut_new : R.string.app_menu_edit);
        root.addView(title);

        // Entrée HDMI : ← → pour changer
        LinearLayout inputRow = fieldRow(R.string.shortcut_input);
        inputValue = text(16, Color.WHITE);
        inputValue.setGravity(Gravity.END);
        inputRow.addView(inputValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputRow.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || inputs.size() < 2) return false;
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            String before = inputs.get(inputIndex)[1];
            inputIndex = Math.floorMod(inputIndex + dir, inputs.size());
            if (!nameTyped && icon.isEmpty() && sc.name.equals(before)) setName(inputs.get(inputIndex)[1]);
            showInput();
            Sounds.play(Sounds.STEP);
            return true;
        });
        root.addView(inputRow, rowLp());
        showInput();

        // Nom : OK ouvre le clavier de la TV
        LinearLayout nameRow = fieldRow(R.string.shortcut_name);
        nameRow.setFocusable(false);
        nameRow.setBackground(null);
        nameField = new EditText(ctx);
        nameField.setSingleLine();
        nameField.setTextColor(Color.WHITE);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameField.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setBackground(focusBackground(dp(10)));
        nameField.setPadding(dp(14), dp(8), dp(14), dp(8));
        setName(sc.name);
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!settingName) nameTyped = true;
            }
        });
        nameRow.addView(nameField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(nameRow, rowLp());

        // Grille des icônes
        TextView iconLabel = text(13, 0x99FFFFFF);
        iconLabel.setText(R.string.shortcut_icon);
        iconLabel.setLetterSpacing(0.12f);
        iconLabel.setPadding(dp(14), dp(14), 0, dp(6));
        root.addView(iconLabel);
        ScrollView scroll = new ScrollView(ctx);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(6), 0, dp(6));
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        int n = InputShortcuts.ICONS.length;
        LinearLayout line = null;
        for (int i = 0; i <= n; i++) {
            if (i % COLUMNS == 0) {
                line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                grid.addView(line);
            }
            line.addView(cell(i < n ? InputShortcuts.ICONS[i] : ""),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Dernière ligne incomplète : des cases vides gardent les colonnes alignées
        for (int i = (n + 1) % COLUMNS; i != 0 && i < COLUMNS; i++) {
            line.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refreshCells();

        // Boutons
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        if (!creating) {
            buttons.addView(button(R.string.shortcut_remove, () -> {
                close();
                listener.removed();
            }));
            View gap = new View(ctx);
            buttons.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        buttons.addView(button(android.R.string.cancel, this::close));
        TextView save = button(R.string.shortcut_save, () -> {
            String name = nameField.getText().toString().trim();
            sc.name = name.isEmpty() ? (inputs.isEmpty() ? sc.name : inputs.get(inputIndex)[1]) : name;
            if (!inputs.isEmpty()) sc.input = inputs.get(inputIndex)[0];
            sc.icon = icon;
            close();
            listener.saved(sc);
        });
        buttons.addView(save);
        root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Ouverture comme le panneau de réglages : fondu et léger zoom
        host.removeAllViews();
        host.addView(root, match());
        host.setVisibility(View.VISIBLE);
        host.setAlpha(0f);
        host.setScaleX(0.96f);
        host.setScaleY(0.96f);
        host.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                .setUpdateListener(a -> host.invalidate()).withEndAction(null).start();
        // Création : on commence par l'icône ; modification : sur l'icône actuelle
        int start = 0;
        for (int i = 0; i < n; i++) if (InputShortcuts.ICONS[i].equals(icon)) start = i;
        if (!creating && icon.isEmpty()) start = n;
        View first = cells.get(start);
        root.post(first::requestFocus);
    }

    boolean isOpen() {
        return open;
    }

    /** Referme le panneau sans rien enregistrer (Annuler, Retour). */
    void close() {
        if (!open) return;
        open = false;
        host.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(220).setUpdateListener(null)
                .withEndAction(() -> {
                    host.setVisibility(View.GONE);
                    host.removeAllViews();
                }).start();
        listener.closed();
    }

    private boolean isInputLabel(String name) {
        for (String[] in : inputs) if (in[1].equals(name)) return true;
        return false;
    }

    private void showInput() {
        String label = inputs.isEmpty() ? InputShortcuts.inputLabel(ctx, sc.input) : inputs.get(inputIndex)[1];
        inputValue.setText(inputs.size() > 1 ? "‹  " + label + "  ›" : label);
    }

    private void setName(String name) {
        settingName = true;
        nameField.setText(name);
        settingName = false;
        sc.name = name;
    }

    /** Une case : l'image (ou le nom pour « sans icône »), son nom dessous. */
    private FrameLayout cell(String key) {
        FrameLayout cell = new FrameLayout(ctx);
        cell.setTag(key);
        cell.setFocusable(true);
        cell.setClipChildren(false);
        cell.setPadding(dp(6), dp(6), dp(6), dp(4));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout art = new FrameLayout(ctx);
        art.setTag("art");
        int res = InputShortcuts.iconRes(key);
        if (res != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(res);
            art.addView(iv, match());
        } else {
            art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A3346, 0xFF121722}));
            TextView none = text(13, 0xCCFFFFFF);
            none.setGravity(Gravity.CENTER);
            none.setText("HDMI");
            art.addView(none, match());
        }
        float r = dp(10);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        art.setClipToOutline(true);
        // Coche de l'icône choisie, dans le coin
        TextView check = text(13, Color.WHITE);
        check.setTag("check");
        check.setText("✓");
        check.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        check.setBackground(dot);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        checkLp.gravity = Gravity.TOP | Gravity.END;
        checkLp.setMargins(0, dp(5), dp(5), 0);
        art.addView(check, checkLp);
        col.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        TextView label = text(12, 0xCCFFFFFF);
        label.setTag("label");
        label.setText(res != 0 ? InputShortcuts.iconName(key) : ctx.getString(R.string.icon_none_short));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine();
        label.setPadding(0, dp(5), 0, 0);
        col.addView(label);
        cell.addView(col, match());

        cell.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(160).start();
            v.setTranslationZ(has ? dp(6) : 0);
            styleCell((FrameLayout) v);
        });
        cell.setOnClickListener(v -> choose(key));
        cell.setOnKeyListener((v, code, e) -> {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.getAction() == KeyEvent.ACTION_UP) {
                choose(key);
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        });
        cells.add(cell);
        return cell;
    }

    private void choose(String key) {
        icon = key;
        Sounds.play(Sounds.SELECT);
        if (!nameTyped) {
            String n = InputShortcuts.iconName(key);
            setName(n != null ? n : (inputs.isEmpty() ? sc.name : inputs.get(inputIndex)[1]));
        }
        refreshCells();
    }

    private void refreshCells() {
        for (FrameLayout c : cells) styleCell(c);
    }

    /** Liseré blanc si la case a le focus, de la couleur d'accent si c'est l'icône choisie. */
    private void styleCell(FrameLayout c) {
        boolean chosen = icon.equals(c.getTag());
        boolean focused = c.hasFocus() || c.isFocused();
        View art = c.findViewWithTag("art");
        View check = c.findViewWithTag("check");
        TextView label = c.findViewWithTag("label");
        check.setVisibility(chosen ? View.VISIBLE : View.GONE);
        if (focused || chosen) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(10));
            // Liseré fin, comme celui de la sélection dans la barre d'applis
            ring.setStroke(Math.max(1, dp(1.5f)), focused ? Color.WHITE : accent);
            art.setForeground(ring);
        } else {
            art.setForeground(null);
        }
        label.setTextColor(focused || chosen ? Color.WHITE : 0x99FFFFFF);
    }

    private LinearLayout fieldRow(int labelRes) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setFocusable(true);
        row.setBackground(focusBackground(dp(12)));
        TextView label = text(16, 0xCCFFFFFF);
        label.setText(labelRes);
        row.addView(label, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private TextView button(int labelRes, Runnable action) {
        TextView b = text(16, Color.WHITE);
        b.setText(labelRes);
        b.setFocusable(true);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setBackground(focusBackground(dp(12)));
        b.setOnClickListener(v -> {
            Sounds.play(Sounds.SELECT);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private StateListDrawable focusBackground(int radius) {
        // Comme les lignes du panneau de réglages : un voile clair, sans contour
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(radius);
        on.setColor(0x33FFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
