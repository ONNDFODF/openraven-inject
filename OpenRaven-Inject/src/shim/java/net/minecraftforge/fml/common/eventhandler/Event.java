package net.minecraftforge.fml.common.eventhandler;

/**
 * What every event in this shim descends from.
 *
 * <p>The real one decides whether an event may be cancelled by looking for a
 * {@code @Cancelable} annotation, which is machinery for a class hierarchy this
 * shim does not reproduce. Everything here is cancellable instead: a listener
 * that cancels something the client does not actually cancel is a bug in that
 * listener, and one that cannot cancel something it should is a missing
 * feature.
 */
public class Event {

    private boolean canceled;

    public boolean isCancelable() {
        return true;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean value) {
        canceled = value;
    }

    public boolean hasResult() {
        return false;
    }

    public void setResult(Object result) {
    }

    public Object getResult() {
        return null;
    }
}