package fr.proscenium.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sons d'interface discrets, synthétisés au premier lancement (aucun fichier audio dans l'appli) :
 * un tic pour déplacer la sélection, un autre pour régler une valeur, deux notes montantes pour
 * valider, deux notes descendantes pour revenir. Activés et dosés depuis les réglages.
 */
final class Sounds {
    static final int MOVE = 0, STEP = 1, SELECT = 2, BACK = 3;
    private static final int RATE = 22050;
    private static final String TAG = "Proscenium";

    private static SoundPool pool;
    private static final int[] ids = new int[4];
    private static volatile boolean enabled = true;
    private static volatile float volume = 0.4f;

    private Sounds() {}

    /** Prépare les sons (à appeler hors du fil principal : écriture des fichiers puis chargement). */
    static synchronized void init(Context ctx) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        File dir = new File(ctx.getCacheDir(), "sounds");
        dir.mkdirs();
        // Fréquences et durées (Hz, ms) : notes courtes, attaque douce, extinction rapide
        float[][][] notes = {
                {{1700, 16}},
                {{1250, 20}},
                {{660, 45}, {990, 70}},
                {{880, 45}, {587, 70}},
        };
        for (int i = 0; i < notes.length; i++) {
            File f = new File(dir, "ui" + i + ".wav");
            try {
                if (!f.exists()) write(f, synth(notes[i]));
                ids[i] = pool.load(f.getPath(), 1);
            } catch (Exception e) {
                Log.w(TAG, "Son d'interface indisponible", e);
            }
        }
    }

    static void configure(boolean on, float vol) {
        enabled = on;
        volume = vol;
    }

    static void play(int which) {
        SoundPool p = pool;
        if (!enabled || p == null || ids[which] == 0) return;
        float v = volume * (which == MOVE || which == STEP ? 0.6f : 1f);
        p.play(ids[which], v, v, 1, 0, 1f);
    }

    /** Suite de notes sinusoïdales, chacune avec une enveloppe douce (montée 3 ms, extinction exponentielle). */
    private static short[] synth(float[][] notes) {
        int total = 0;
        for (float[] n : notes) total += (int) (RATE * n[1] / 1000);
        short[] pcm = new short[total];
        int at = 0;
        for (float[] n : notes) {
            int len = (int) (RATE * n[1] / 1000), attack = RATE * 3 / 1000;
            for (int i = 0; i < len; i++) {
                double env = Math.min(1.0, i / (double) attack) * Math.exp(-4.0 * i / len);
                // Un peu d'harmonique 2 pour un timbre plus rond qu'une sinusoïde pure
                double s = Math.sin(2 * Math.PI * n[0] * i / RATE) + 0.25 * Math.sin(4 * Math.PI * n[0] * i / RATE);
                pcm[at + i] = (short) (s / 1.25 * env * 0.7 * Short.MAX_VALUE);
            }
            at += len;
        }
        return pcm;
    }

    private static void write(File f, short[] pcm) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE)
                .putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
    }
}
