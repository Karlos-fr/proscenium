// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A screen of the remote page (format v1): a title and a list of {@link Item}s.
 *
 * <pre>
 * Screen.of("weather", "Weather").subtitle("Paris").add(Item.toggle(…)).add(Item.slider(…))
 * </pre>
 */
public final class Screen {
    final JSONObject json = new JSONObject();
    private final JSONArray items = new JSONArray();

    private Screen(String id, String title) {
        Item.put(json, "id", id);
        Item.put(json, "title", title == null ? "" : title);
        Item.put(json, "subtitle", "");
        Item.put(json, "items", items);
    }

    public static Screen of(String id, String title) { return new Screen(id, title); }

    public Screen subtitle(String s) {
        Item.put(json, "subtitle", s == null ? "" : s);
        return this;
    }

    /**
     * @deprecated the tabs are listed by {@link AppInfo#tab} only (the page never read this);
     * kept so that older code compiles, it does nothing.
     */
    @Deprecated
    public Screen tab(String icon, int order) { return this; }

    public Screen add(Item item) {
        if (item != null) items.put(item.json);
        return this;
    }

    /**
     * Paged screen (long lists): {@code total} entries in all, this answer holds the ones from
     * {@code offset}; {@code search}: the page shows a search field and sends {@code q}.
     */
    public Screen paged(int total, int offset, int limit, boolean search) {
        JSONObject p = new JSONObject();
        Item.put(p, "total", total);
        Item.put(p, "offset", offset);
        Item.put(p, "limit", limit);
        Item.put(p, "search", search);
        Item.put(json, "paged", p);
        return this;
    }

    public Screen extra(String key, Object value) {
        Item.put(json, key, value);
        return this;
    }
}
