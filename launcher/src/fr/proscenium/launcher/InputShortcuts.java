package fr.proscenium.launcher;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Raccourcis de la barre d'applis vers une entrée HDMI de la TV (une console branchée…),
 * avec leur nom et leur icône. Dans la barre, un raccourci s'écrit « input:&lt;id&gt; » ;
 * sa description est rangée dans le réglage {@link Settings#inputShortcuts} (JSON).
 */
final class InputShortcuts {
    static final String PREFIX = "input:";

    /** Icônes de consoles fournies : clé, nom proposé, image. */
    static final String[] ICONS = {
            "nes", "snes", "n64", "gamecube", "wii", "wiiu", "switch", "switch2",
            "ps1", "ps2", "ps3", "ps4", "ps5",
            "xbox", "xbox360", "xboxone", "xboxseriess", "xboxseriesx",
            "mastersystem", "megadrive", "saturn", "dreamcast", "neogeo", "pcengine"};
    static final String[] ICON_NAMES = {
            "NES", "Super Nintendo", "Nintendo 64", "GameCube", "Wii", "Wii U", "Switch", "Switch 2",
            "PS1", "PS2", "PS3", "PS4", "PS5",
            "Xbox", "Xbox 360", "Xbox One", "Xbox Series S", "Xbox Series X",
            "Master System", "Mega Drive", "Saturn", "Dreamcast", "Neo Geo", "PC Engine"};
    static final int[] ICON_RES = {
            R.drawable.console_nes, R.drawable.console_snes, R.drawable.console_n64, R.drawable.console_gamecube,
            R.drawable.console_wii, R.drawable.console_wiiu, R.drawable.console_switch, R.drawable.console_switch2,
            R.drawable.console_ps1, R.drawable.console_ps2, R.drawable.console_ps3, R.drawable.console_ps4,
            R.drawable.console_ps5,
            R.drawable.console_xbox, R.drawable.console_xbox360, R.drawable.console_xboxone,
            R.drawable.console_xboxseriess, R.drawable.console_xboxseriesx,
            R.drawable.console_mastersystem, R.drawable.console_megadrive, R.drawable.console_saturn,
            R.drawable.console_dreamcast, R.drawable.console_neogeo, R.drawable.console_pcengine};

    static String iconName(String icon) {
        for (int i = 0; i < ICONS.length; i++) if (ICONS[i].equals(icon)) return ICON_NAMES[i];
        return null;
    }

    /** Nom de l'entrée HDMI (« HDMI 2 ») d'après son identifiant, sinon l'identifiant lui-même. */
    static String inputLabel(Context ctx, String inputId) {
        for (String[] in : hdmiInputs(ctx)) if (in[0].equals(inputId)) return in[1];
        return inputId;
    }

    static final class Shortcut {
        String id, input, name, icon;
    }

    private InputShortcuts() {}

    static boolean isShortcut(String entry) {
        return entry != null && entry.startsWith(PREFIX);
    }

    static List<Shortcut> load(Settings s) {
        List<Shortcut> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s.inputShortcuts.value);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Shortcut sc = new Shortcut();
                sc.id = o.getString("id");
                sc.input = o.getString("input");
                sc.name = o.optString("name");
                sc.icon = o.optString("icon");
                out.add(sc);
            }
        } catch (Exception e) {
            Log.w(HomeBase.TAG, "Raccourcis HDMI illisibles", e);
        }
        return out;
    }

    static void save(Settings s, List<Shortcut> list) {
        JSONArray a = new JSONArray();
        try {
            for (Shortcut sc : list) {
                a.put(new JSONObject().put("id", sc.id).put("input", sc.input).put("name", sc.name).put("icon", sc.icon));
            }
        } catch (Exception e) {
            Log.w(HomeBase.TAG, "Raccourcis HDMI non enregistrés", e);
            return;
        }
        s.inputShortcuts.value = a.toString();
        s.changed(s.inputShortcuts);
    }

    /** Raccourci d'une entrée de la barre (« input:… »), ou null. */
    static Shortcut find(Settings s, String entry) {
        if (!isShortcut(entry)) return null;
        String id = entry.substring(PREFIX.length());
        for (Shortcut sc : load(s)) if (sc.id.equals(id)) return sc;
        return null;
    }

    static Shortcut create(Settings s, String input, String name, String icon) {
        List<Shortcut> list = load(s);
        Shortcut sc = new Shortcut();
        sc.id = Long.toString(System.currentTimeMillis(), 36);
        sc.input = input;
        sc.name = name;
        sc.icon = icon;
        list.add(sc);
        save(s, list);
        return sc;
    }

    static void update(Settings s, Shortcut changed) {
        List<Shortcut> list = load(s);
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(changed.id)) list.set(i, changed);
        save(s, list);
    }

    static void remove(Settings s, String entry) {
        List<Shortcut> list = load(s);
        String id = entry.substring(PREFIX.length());
        list.removeIf(sc -> sc.id.equals(id));
        save(s, list);
    }

    static int iconRes(String icon) {
        for (int i = 0; i < ICONS.length; i++) if (ICONS[i].equals(icon)) return ICON_RES[i];
        return 0;
    }

    /** Entrées HDMI de la TV : identifiant et nom (« HDMI 1 »…). Vide sur une box sans entrée. */
    static List<String[]> hdmiInputs(Context ctx) {
        List<String[]> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.isHidden(ctx)) continue;
                // Les entrées HDMI « filles » (appareils CEC) ont un parent : on ne garde que les prises
                if (info.getParentId() != null) continue;
                CharSequence custom = info.loadCustomLabel(ctx);
                CharSequence label = custom != null && custom.length() > 0 ? custom : info.loadLabel(ctx);
                out.add(new String[]{info.getId(), String.valueOf(label)});
            }
            out.sort((a, b) -> a[1].compareTo(b[1]));
        } catch (Exception e) {
            Log.w(HomeBase.TAG, "Entrées TV illisibles", e);
        }
        return out;
    }

    /** Ouvre l'appli TV du système sur cette entrée HDMI. */
    static Intent launchIntent(String inputId) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
    }
}
