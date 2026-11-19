package fr.proscenium.launcher;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Météo actuelle et recherche de ville via Open-Meteo (gratuit, sans clé). Appels réseau : hors fil principal. */
final class Weather {
    static final class Now {
        final int temp, code;
        final boolean day;
        /** Vent moyen et rafales (km/h, ou mph avec les °F) ; direction d'où vient le vent, en degrés. */
        int wind, gusts, windFrom;

        Now(int temp, int code, boolean day) {
            this.temp = temp;
            this.code = code;
            this.day = day;
        }

        /** Flèche vers où souffle le vent (la direction météo donne d'où il vient). */
        String windArrow() {
            String[] arrows = {"\u2193", "\u2199", "\u2190", "\u2196", "\u2191", "\u2197", "\u2192", "\u2198"};
            return arrows[Math.floorMod(Math.round(windFrom / 45f), 8)];
        }

        IconView.Kind icon() {
            if (code == 0) return day ? IconView.Kind.SUN : IconView.Kind.MOON;
            if (code <= 2) return day ? IconView.Kind.PARTLY_DAY : IconView.Kind.PARTLY_NIGHT;
            if (code == 3) return IconView.Kind.CLOUD;
            if (code == 45 || code == 48) return IconView.Kind.FOG;
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return IconView.Kind.SNOW;
            if (code >= 95) return IconView.Kind.STORM;
            return IconView.Kind.RAIN;
        }
    }

    private Weather() {}

    /** {@code coords} : « lat,lon » ; {@code fahrenheit} : unité de la température renvoyée. */
    static Now fetch(String coords, boolean fahrenheit) throws Exception {
        String[] ll = coords.split(",");
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m,wind_direction_10m"
                        + "&timezone=auto%s",
                ll[0].trim(), ll[1].trim(), fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
        JSONObject cur = new JSONObject(Net.getText(url)).getJSONObject("current");
        Now now = new Now((int) Math.round(cur.getDouble("temperature_2m")), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
        now.wind = (int) Math.round(cur.optDouble("wind_speed_10m", 0));
        now.gusts = (int) Math.round(cur.optDouble("wind_gusts_10m", 0));
        now.windFrom = (int) Math.round(cur.optDouble("wind_direction_10m", 0));
        return now;
    }

    /** Géocodage : renvoie {nom, « lat,lon »} de la première ville trouvée, ou null. */
    /**
     * Villes de ce nom (20 au plus, les plus peuplées d'abord) : {nom, "lat,lon", « Nom, région,
     * pays »}. Liste vide si aucune. Plusieurs villes portent souvent le même nom : l'appelant
     * laisse choisir la bonne.
     */
    static java.util.List<String[]> searchCities(String query, String language) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=20&format=json&name=" + Uri.encode(query)
                + "&language=" + language;
        JSONArray results = new JSONObject(Net.getText(url)).optJSONArray("results");
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String name = r.getString("name");
            StringBuilder label = new StringBuilder(name);
            for (String key : new String[]{"admin2", "admin1", "country"}) {
                String part = r.optString(key, "");
                if (!part.isEmpty() && !part.equals(name) && label.indexOf(", " + part) < 0) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[]{name,
                    String.format(Locale.US, "%.4f,%.4f", r.getDouble("latitude"), r.getDouble("longitude")),
                    label.toString()});
        }
        return out;
    }
}
