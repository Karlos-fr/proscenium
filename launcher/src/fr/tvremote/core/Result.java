// TV Remote core 0.2.0 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

/** Outcome of a set or an action. */
public final class Result {
    final boolean ok;
    final String error, code;
    String open;

    private Result(boolean ok, String error, String code) {
        this.ok = ok;
        this.error = error;
        this.code = code;
    }

    public static Result ok() { return new Result(true, null, null); }

    /** {@code text}: already translated, shown as is on the phone. */
    public static Result error(String text) { return new Result(false, text, null); }

    public static Result error(String text, String code) { return new Result(false, text, code); }

    /** The page then shows this screen (actions only). */
    public Result open(String screenId) {
        open = screenId;
        return this;
    }
}
