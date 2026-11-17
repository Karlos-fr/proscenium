package fr.proscenium.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.LinearLayout;
import android.widget.TextSwitcher;
import android.widget.TextView;

/**
 * Horloge « HH:mm », avec les secondes « :ss » affichables à la demande (mode cinéma).
 * Chaque chiffre qui change glisse vers le haut : l'ancien monte et s'efface,
 * le nouveau arrive par le bas. Chaque chiffre est découpé dans sa propre case,
 * le glissement reste donc net.
 */
final class RollingClock extends LinearLayout {
    interface Styler { void style(TextView t); }

    private final TextSwitcher[] digits = new TextSwitcher[6];
    /** Deux-points entre heures et minutes, puis entre minutes et secondes. */
    private final TextView[] colons = new TextView[2];
    private final String[] shown = new String[6];
    private final LinearLayout seconds;
    private boolean secondsOn;

    RollingClock(Context ctx, Styler styler, Styler secondsStyler) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.BOTTOM);
        seconds = new LinearLayout(ctx);
        seconds.setOrientation(HORIZONTAL);
        seconds.setVisibility(GONE);
        seconds.setAlpha(0f);
        for (int i = 0; i < 6; i++) {
            Styler s = i < 4 ? styler : secondsStyler;
            LinearLayout parent = i < 4 ? this : seconds;
            if (i == 2 || i == 4) {
                TextView colon = new TextView(ctx);
                s.style(colon);
                colon.setText(":");
                parent.addView(colon);
                colons[i / 2 - 1] = colon;
            }
            TextSwitcher sw = new TextSwitcher(ctx);
            sw.setFactory(() -> {
                TextView t = new TextView(ctx);
                s.style(t);
                t.setGravity(Gravity.CENTER);
                return t;
            });
            sw.setInAnimation(slide(0.65f, 0f, 0f, 1f, 480, new DecelerateInterpolator(1.6f)));
            sw.setOutAnimation(slide(0f, -0.65f, 1f, 0f, 360, new AccelerateInterpolator(1.4f)));
            sw.setAnimateFirstView(false);
            digits[i] = sw;
            parent.addView(sw);
        }
        addView(seconds);
    }

    /** {@code hhmmss} : 6 chiffres, par exemple « 210755 ». Seuls les chiffres modifiés glissent. */
    void setTime(String hhmmss, boolean animate) {
        int n = secondsOn ? 6 : 4;
        for (int i = 0; i < 6; i++) {
            String d = String.valueOf(hhmmss.charAt(i));
            if (d.equals(shown[i])) continue;
            // Secondes cachées : mises à jour sans animation, pour être justes quand elles apparaissent
            if (animate && i < n && shown[i] != null) digits[i].setText(d);
            else digits[i].setCurrentText(d);
            shown[i] = d;
        }
    }

    /** Réapplique un style (taille…) à tous les chiffres, sans recréer l'horloge : aperçu en direct. */
    void restyle(Styler styler, Styler secondsStyler) {
        for (int i = 0; i < 6; i++) {
            Styler s = i < 4 ? styler : secondsStyler;
            for (int c = 0; c < digits[i].getChildCount(); c++) s.style((TextView) digits[i].getChildAt(c));
        }
        styler.style(colons[0]);
        secondsStyler.style(colons[1]);
        requestLayout();
    }

    /** Affiche ou cache les secondes, en fondu. */
    void showSeconds(boolean on) {
        if (on == secondsOn) return;
        secondsOn = on;
        seconds.animate().cancel();
        if (on) {
            seconds.setVisibility(VISIBLE);
            seconds.animate().alpha(1f).setDuration(900).withEndAction(null).start();
        } else {
            seconds.animate().alpha(0f).setDuration(300).withEndAction(() -> seconds.setVisibility(GONE)).start();
        }
    }

    private static Animation slide(float fromY, float toY, float fromA, float toA, long ms, android.view.animation.Interpolator in) {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0, Animation.RELATIVE_TO_SELF, 0,
                Animation.RELATIVE_TO_SELF, fromY, Animation.RELATIVE_TO_SELF, toY));
        set.addAnimation(new AlphaAnimation(fromA, toA));
        set.setDuration(ms);
        set.setInterpolator(in);
        return set;
    }
}
