package fr.proscenium.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Dès que l'accueil Google passe au premier plan (bouton Accueil, ou lancement
 * forcé par l'appli Free BootLock à la fin du démarrage), on affiche Proscenium
 * à sa place, comme une vraie tâche d'accueil. Le filtre de paquets est dans
 * res/xml/home_guard.xml : le service ne reçoit que les événements de ces accueils.
 *  - Android TV classique (Freebox) : com.google.android.leanbacklauncher ;
 *  - Google TV (Sony Bravia…) : com.google.android.apps.tv.launcherx, dont seul l'écran
 *    d'accueil est remplacé (la liste des applis, la recherche, les profils restent accessibles).
 */
public class HomeGuardService extends AccessibilityService {
    private static final String LEANBACK_LAUNCHER = "com.google.android.leanbacklauncher";
    private static final String GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx";
    private long lastLaunch;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(e.getPackageName()), cls = String.valueOf(e.getClassName());
        boolean googleHome = pkg.equals(LEANBACK_LAUNCHER)
                || (pkg.equals(GOOGLE_TV_LAUNCHER) && cls.endsWith(".HomeActivity"));
        if (!googleHome) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 800) return;
        lastLaunch = now;

        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
    }

    @Override
    public void onInterrupt() {
    }
}
