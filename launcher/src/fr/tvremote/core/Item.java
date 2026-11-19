// TV Remote core 0.1.2 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One line of a screen (format v1): header, info, toggle, choice, slider, text, button, grid, list.
 * Built with the static factories, then the chainable setters. The JSON is written as you go.
 *
 * <pre>
 * Item.slider("speed", "Speed", 40, 0, 100, 5).format("%d %%").help("How fast…")
 * Item.choice("mode", "Mode", 1).option(0, "Off").option(1, "Real")
 * Item.grid("packs", "Packs", Item.CHECK_MULTI).tile(Item.tile("films80", "80s").img("img/p80").checked(true))
 * </pre>
 */
public final class Item {
    public static final String CHECK_NONE = "none", CHECK_SINGLE = "single", CHECK_MULTI = "multi";

    final JSONObject json = new JSONObject();

    private Item(String type, String id, String label) {
        put(json, "type", type);
        if (id != null) put(json, "id", id);
        put(json, "label", label == null ? "" : label);
    }

    // ------------------------------------------------------------------ factories

    public static Item header(String label) { return new Item("header", null, label); }

    /** Read-only line: a label and a value shown at right. */
    public static Item info(String id, String label, String value) {
        return new Item("info", id, label).value(value);
    }

    public static Item toggle(String id, String label, boolean value) {
        return new Item("toggle", id, label).value(value);
    }

    /** List of options; add them with {@link #option}. {@code value} is the chosen option's value. */
    public static Item choice(String id, String label, Object value) {
        Item i = new Item("choice", id, label).value(value);
        put(i.json, "options", new JSONArray());
        return i;
    }

    public static Item slider(String id, String label, double value, double min, double max, double step) {
        Item i = new Item("slider", id, label).value(value);
        put(i.json, "min", min);
        put(i.json, "max", max);
        put(i.json, "step", step);
        return i;
    }

    public static Item text(String id, String label, String value) {
        return new Item("text", id, label).value(value == null ? "" : value);
    }

    /**
     * Secret text (an API key…): its value never leaves the TV. The page only learns whether
     * one is set, and may send a new one.
     */
    public static Item secret(String id, String label, boolean isSet) {
        Item i = new Item("text", id, label);
        put(i.json, "secret", true);
        put(i.json, "value", "");
        put(i.json, "isSet", isSet);
        return i;
    }

    public static Item button(String id, String label) { return new Item("button", id, label); }

    /** Tiles with pictures; add them with {@link #tile}. {@code check}: CHECK_NONE, CHECK_SINGLE or CHECK_MULTI. */
    public static Item grid(String id, String label, String check) {
        Item i = new Item("grid", id, label);
        put(i.json, "check", check);
        put(i.json, "tiles", new JSONArray());
        return i;
    }

    /** Rows; add them with {@link #row}. {@code check}: CHECK_NONE, CHECK_SINGLE or CHECK_MULTI. */
    public static Item list(String id, String label, String check) {
        Item i = new Item("list", id, label);
        put(i.json, "check", check);
        put(i.json, "rows", new JSONArray());
        return i;
    }

    /** A tile of a grid, or a row of a list. Its id must not contain '/'. */
    public static Entry tile(String id, String label) { return new Entry(id, label); }

    public static Entry row(String id, String label) { return new Entry(id, label); }

    // ------------------------------------------------------------------ common fields

    public Item help(String text) {
        if (text != null && !text.isEmpty()) put(json, "help", text);
        return this;
    }

    /** Small label next to the line; the skin colours it by {@code kind} (cpu, experimental, internet…). */
    public Item badge(String text, String kind) {
        if (text != null && !text.isEmpty()) put(json, "badge", badgeJson(text, kind));
        return this;
    }

    public Item enabled(boolean on) {
        if (!on) put(json, "enabled", false);
        return this;
    }

    /** Current value (also the text shown at right of a button). */
    public Item value(Object v) {
        if (!"true".equals(String.valueOf(json.opt("secret")))) put(json, "value", v);
        return this;
    }

    // ------------------------------------------------------------------ type fields

    /** choice: one option. */
    public Item option(Object value, String label) {
        JSONObject o = new JSONObject();
        put(o, "value", value);
        put(o, "label", label);
        json.optJSONArray("options").put(o);
        return this;
    }

    /** slider: how the value is written, e.g. "%d %%", "%d s". */
    public Item format(String f) {
        put(json, "format", f);
        return this;
    }

    /** text: hint shown in the empty field. */
    public Item placeholder(String p) {
        put(json, "placeholder", p);
        return this;
    }

    /** button: question asked on the phone before the action is sent. */
    public Item confirm(String question) {
        put(json, "confirm", question);
        return this;
    }

    /** button, grid: screen opened when tapped (no action is sent for a button with opens). */
    public Item opens(String screenId) {
        put(json, "opens", screenId);
        return this;
    }

    /** grid: one tile. */
    public Item tile(Entry t) {
        json.optJSONArray("tiles").put(t.json);
        return this;
    }

    /** list: one row. */
    public Item row(Entry r) {
        json.optJSONArray("rows").put(r.json);
        return this;
    }

    /** list: the rows can be moved; the page sends the new order as {@code set {id, value:[ids]}}. */
    public Item order(boolean movable) {
        if (movable) put(json, "order", true);
        return this;
    }

    /** Any other field, for an app's own skin. */
    public Item extra(String key, Object value) {
        put(json, key, value);
        return this;
    }

    // ------------------------------------------------------------------ tiles and rows

    public static final class Entry {
        final JSONObject json = new JSONObject();

        Entry(String id, String label) {
            if (id.indexOf('/') >= 0) throw new IllegalArgumentException("'/' in id: " + id);
            put(json, "id", id);
            put(json, "label", label == null ? "" : label);
        }

        /** Picture: a route of the page, usually "img/<key>" served by {@link TvRemote.App#image}. */
        public Entry img(String url) { put(json, "img", url); return this; }

        /** Second line (row) or caption under the label (tile). */
        public Entry sub(String text) { put(json, "sub", text); return this; }

        public Entry checked(boolean on) { put(json, "checked", on); return this; }

        public Entry badge(String text, String kind) { put(json, "badge", badgeJson(text, kind)); return this; }

        public Entry enabled(boolean on) { if (!on) put(json, "enabled", false); return this; }

        public Entry extra(String key, Object value) { put(json, key, value); return this; }
    }

    // ------------------------------------------------------------------ JSON helpers

    static JSONObject badgeJson(String text, String kind) {
        JSONObject b = new JSONObject();
        put(b, "text", text);
        put(b, "kind", kind == null ? "" : kind);
        return b;
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) {
                o.put(k, ((Double) v).longValue());   // 40 instead of 40.0
            } else {
                o.put(k, v == null ? JSONObject.NULL : v);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
