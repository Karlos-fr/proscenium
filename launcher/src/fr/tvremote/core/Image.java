// TV Remote core 0.2.1 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

/** A picture served at img/&lt;key&gt;. */
public final class Image {
    final byte[] data;
    final String mime, etag;

    /** {@code mime}: "image/webp", "image/png", "image/jpeg"…; {@code etag}: null, or a version of the picture. */
    public Image(byte[] data, String mime, String etag) {
        this.data = data;
        this.mime = mime;
        this.etag = etag;
    }

    public static Image jpeg(byte[] data) { return new Image(data, "image/jpeg", null); }

    public static Image png(byte[] data) { return new Image(data, "image/png", null); }

    public static Image webp(byte[] data) { return new Image(data, "image/webp", null); }
}
