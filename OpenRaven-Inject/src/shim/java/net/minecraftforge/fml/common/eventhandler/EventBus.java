package net.minecraftforge.fml.common.eventhandler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A bus that does what the client asks of Forge's: subscribe an object, call
 * its {@code @SubscribeEvent} methods when an event arrives, and let an event
 * be cancelled.
 *
 * <p>It is deliberately smaller than Forge's. There are no listener lists per
 * class, no generation counters and no result handling: the client registers a
 * handful of objects and posts a handful of events per frame, and what matters
 * is that the method with the event parameter is the one that runs, in priority
 * order, with the event object the game produced.
 */
public final class EventBus {

    private final List<Object> listeners = new CopyOnWriteArrayList<Object>();
    private final Map<Class<?>, List<Method>> methods = new ConcurrentHashMap<Class<?>, List<Method>>();

    public void register(Object target) {
        if (target == null || listeners.contains(target)) {
            return;
        }
        listeners.add(target);
    }

    public void unregister(Object target) {
        listeners.remove(target);
    }

    public boolean post(Event event) {
        if (event == null) {
            return false;
        }
        for (Object listener : listeners) {
            for (Method method : handlersOf(listener.getClass())) {
                dispatch(listener, method, event);
            }
        }
        return event.isCanceled();
    }

    /** Every {@code @SubscribeEvent} method on a class, in priority order. */
    private List<Method> handlersOf(Class<?> type) {
        List<Method> known = methods.get(type);
        if (known != null) {
            return known;
        }
        List<Method> found = new ArrayList<Method>();
        for (Method method : type.getMethods()) {
            if (method.getAnnotation(SubscribeEvent.class) == null
                    || Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 1) {
                continue;
            }
            found.add(method);
        }
        Collections.sort(found, new Comparator<Method>() {
            @Override
            public int compare(Method left, Method right) {
                return left.getAnnotation(SubscribeEvent.class).priority().ordinal()
                        - right.getAnnotation(SubscribeEvent.class).priority().ordinal();
            }
        });
        methods.put(type, found);
        return found;
    }

    private void dispatch(Object listener, Method method, Event event) {
        if (!method.getParameterTypes()[0].isInstance(event)) {
            return;
        }
        SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
        if (event.isCanceled() && annotation != null && !annotation.receiveCanceled()) {
            return;
        }
        try {
            method.setAccessible(true);
            method.invoke(listener, event);
        } catch (Throwable failed) {
            // A listener that throws must not take the game's tick with it.
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            System.err.println("[openraven] event listener " + listener.getClass().getName()
                    + "." + method.getName() + " failed: " + cause);
        }
    }
}