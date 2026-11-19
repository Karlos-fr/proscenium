// TV Remote core 0.2.0 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Answer of api/app: the app, its language, its theme, its root screen and its tabs. */
public final class AppInfo {
    final JSONObject json = new JSONObject();
    private final JSONObject theme = new JSONObject();
    private final JSONArray tabs = new JSONArray();

    /** {@code root}: screen shown first. */
    public AppInfo(String app, String version, String root) {
        Item.put(json, "format", TvRemote.FORMAT);
        Item.put(json, "core", TvRemote.VERSION);
        Item.put(json, "app", app);
        Item.put(json, "version", version);
        Item.put(json, "lang", "en");
        Item.put(json, "root", root);
        Item.put(json, "theme", theme);
        Item.put(json, "tabs", tabs);
    }

    /** Language of the labels (en, fr, de…); also used for the page's own few words. */
    public AppInfo lang(String code) {
        Item.put(json, "lang", code);
        return this;
    }

    /** Accent colour (ARGB, alpha ignored), written "#RRGGBB". */
    public AppInfo accent(int argb) {
        return theme("accent", String.format("#%06X", argb & 0xFFFFFF));
    }

    /** Any other theme value for the skin. */
    public AppInfo theme(String key, Object value) {
        Item.put(theme, key, value);
        return this;
    }

    /** A top-level tab, in order. */
    public AppInfo tab(String screenId, String title, String icon) {
        JSONObject t = new JSONObject();
        Item.put(t, "id", screenId);
        Item.put(t, "title", title);
        if (icon != null) Item.put(t, "icon", icon);
        tabs.put(t);
        return this;
    }

    public AppInfo extra(String key, Object value) {
        Item.put(json, key, value);
        return this;
    }
}
