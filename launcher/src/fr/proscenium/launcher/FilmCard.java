package fr.proscenium.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fiche du film sous le titre de la carte du mode cinéma (accueil et économiseur d'écran) : une ligne
 * d'infos (année, durée, genres, note) et l'accroche ou le résumé. La note s'affiche en étoile
 * (« ★ 8,4 ») ou dans un anneau coloré en pourcentage, à la façon de TMDB. Le résumé complet, s'il
 * dépasse cinq lignes, défile lentement.
 */
final class FilmCard {
    /** Anneau de la note, en pourcentage (vert, jaune ou rouge comme sur TMDB). */
    static final class RatingRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private int percent;

        RatingRing(Context ctx) {
            super(ctx);
        }

        void setPercent(int p) {
            percent = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f, stroke = r * 0.16f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xD9081C22);
            c.drawCircle(w / 2, h / 2, r, paint);
            int color = percent >= 70 ? 0xFF21D07A : percent >= 40 ? 0xFFD2D531 : 0xFFDB2360;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            arc.set(w / 2 - r + stroke * 1.4f, h / 2 - r + stroke * 1.4f, w / 2 + r - stroke * 1.4f, h / 2 + r - stroke * 1.4f);
            paint.setColor((color & 0x00FFFFFF) | 0x55000000);
            c.drawArc(arc, 0, 360, false, paint);
            paint.setColor(color);
            c.drawArc(arc, -90, 360f * percent / 100f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(r * 0.78f);
            Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(String.valueOf(percent), w / 2, h / 2 - (fm.ascent + fm.descent) / 2, paint);
        }
    }

    private final Context ctx;
    private final float density;
    final LinearLayout infoRow;
    private final RatingRing ring;
    private final TextView info, summary;
    private final FrameLayout summaryBox;
    private ValueAnimator scroll;
    /** Numéro du texte affiché : un défilement prévu pour un ancien texte ne démarre pas. */
    private int generation;

    FilmCard(Context ctx) {
        this.ctx = ctx;
        density = ctx.getResources().getDisplayMetrics().density;
        infoRow = new LinearLayout(ctx);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setPadding(dp(2), dp(8), dp(2), 0);
        ring = new RatingRing(ctx);
        info = text(14, 0xCCFFFFFF);
        info.setLetterSpacing(0.08f);
        info.setSingleLine();
        info.setEllipsize(TextUtils.TruncateAt.END);
        summary = text(15, 0xE6FFFFFF);
        summary.setLineSpacing(0, 1.15f);
        summaryBox = new FrameLayout(ctx) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                // Le texte garde toute sa hauteur (une boîte de hauteur fixe le rognerait à sa
                // taille, sans rien à faire défiler) : la boîte le coupe, il défile dedans
                View c = getChildAt(0);
                if (c == null || c.getVisibility() == GONE) return;
                int cw = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
        };
        summaryBox.setPadding(dp(2), dp(10), dp(2), 0);
        summaryBox.addView(summary, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
        return t;
    }

    /** Ajoute la fiche sous le titre, dans la carte ({@code side} : Gravity.START ou END). */
    void attach(LinearLayout card, int side) {
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(infoRow, rowLp);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(summaryBox, boxLp);
        setSide(side);
    }

    /** Côté de la carte : les textes s'alignent sur le logo. */
    void setSide(int side) {
        ((LinearLayout.LayoutParams) infoRow.getLayoutParams()).gravity = side;
        ((LinearLayout.LayoutParams) summaryBox.getLayoutParams()).gravity = side;
        summary.setGravity(side);
        infoRow.removeAllViews();
        int size = dp(30);
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(size, size);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        // L'anneau se met du côté du bord de l'écran, le texte vers le centre
        if (side == Gravity.END) {
            infoLp.rightMargin = dp(10);
            infoRow.addView(info, infoLp);
            infoRow.addView(ring, ringLp);
        } else {
            ringLp.rightMargin = dp(10);
            infoRow.addView(ring, ringLp);
            infoRow.addView(info, infoLp);
        }
        infoRow.requestLayout();
    }

    /**
     * Remplit la fiche. {@code film} : null pour une photo sans film (Bing) ; {@code year} : année
     * tirée du titre ; renvoie vrai si la ligne d'infos remplace la ligne de l'année.
     */
    boolean fill(Playlists.Film film, String year, Settings s) {
        stopScroll();
        generation++;
        float k = s.titleTextSize.f();
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * k);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * k);
        if (film == null || s.titleMode.index == Settings.TITLE_NONE) {
            infoRow.setVisibility(View.GONE);
            summaryBox.setVisibility(View.GONE);
            return false;
        }
        Locale loc = Lang.locale(ctx);
        List<String> parts = new ArrayList<>();
        if (s.showYear.value && !year.isEmpty()) parts.add(year);
        if (s.titleInfo.value) {
            if (film.runtime > 0) {
                int hh = film.runtime / 60, mm = film.runtime % 60;
                parts.add(hh > 0 ? ctx.getString(R.string.runtime_hm, hh, mm) : ctx.getString(R.string.runtime_m, mm));
            }
            if (!film.genres.isEmpty()) {
                String[] g = film.genres.split(", ");
                parts.add(g.length > 2 ? g[0] + ", " + g[1] : film.genres);
            }
        }
        boolean rated = film.rating > 0 && film.votes >= 10;
        if (rated && s.titleRating.index == Settings.RATING_STARS) {
            parts.add("★ " + String.format(loc, "%.1f", film.rating));
        }
        boolean ringOn = rated && s.titleRating.index == Settings.RATING_PERCENT;
        ring.setVisibility(ringOn ? View.VISIBLE : View.GONE);
        if (ringOn) ring.setPercent((int) Math.round(film.rating * 10));
        info.setText(TextUtils.join("  ·  ", parts).toUpperCase(loc));
        info.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
        infoRow.setVisibility(parts.isEmpty() && !ringOn ? View.GONE : View.VISIBLE);

        String text = "";
        int mode = s.titleSummary.index;
        if (mode == Settings.SUMMARY_TAGLINE) text = film.tagline.isEmpty() ? film.overview : film.tagline;
        else if (mode != Settings.SUMMARY_NONE) text = film.overview;
        summaryBox.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(text);
        summary.setTranslationY(0);
        boolean full = mode == Settings.SUMMARY_FULL;
        summary.setMaxLines(full ? Integer.MAX_VALUE : 3);
        summary.setEllipsize(full ? null : TextUtils.TruncateAt.END);
        // Résumé complet : boîte de cinq lignes, le texte qui dépasse défile lentement, puis reprend
        ViewGroup.LayoutParams lp = summaryBox.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (full && !text.isEmpty()) {
            int lineH = summary.getLineHeight();
            int box = lineH * 5 + summaryBox.getPaddingTop();
            lp.height = box;
            summaryBox.setClipChildren(true);
            int gen = generation;
            summary.post(() -> startScroll(box - summaryBox.getPaddingTop(), 0, gen));
        }
        summaryBox.setLayoutParams(lp);
        return infoRow.getVisibility() == View.VISIBLE;
    }

    private void startScroll(int visible, int tries, int gen) {
        if (gen != generation) return;
        // Attend la mise en page du nouveau texte (sa hauteur n'est connue qu'après)
        if ((summary.isLayoutRequested() || summaryBox.isLayoutRequested()) && tries < 20) {
            summary.postDelayed(() -> startScroll(visible, tries + 1, gen), 100);
            return;
        }
        int overflow = summary.getHeight() - visible;
        if (overflow <= 0) return;
        scroll = ValueAnimator.ofFloat(0, -overflow);
        // Environ une ligne toutes les deux secondes, après six secondes de lecture
        scroll.setDuration(Math.max(4000, Math.round(overflow / (float) summary.getLineHeight() * 2000)));
        scroll.setStartDelay(6000);
        scroll.setInterpolator(new LinearInterpolator());
        scroll.addUpdateListener(a -> summary.setTranslationY((float) a.getAnimatedValue()));
        scroll.setRepeatCount(ValueAnimator.INFINITE);
        scroll.setRepeatMode(ValueAnimator.REVERSE);
        scroll.start();
    }

    private void stopScroll() {
        if (scroll != null) scroll.cancel();
        scroll = null;
    }
}
