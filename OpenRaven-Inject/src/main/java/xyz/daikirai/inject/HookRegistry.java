package xyz.daikirai.inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hook table: what to inject, where, and which callback to call.
 *
 * This is the injected build's replacement for {@code @Inject} annotations.
 * A Mixin annotation is read by the Mixin processor at load time and the
 * injection is validated against the target it finds; here the same
 * information is data, so an injection point that no longer exists is a
 * reported failure rather than a build error. That trade is deliberate --
 * see {@link HookTransformer} and the hook report in {@link NativeBridge}.
 *
 * Owners are written in MCP form. {@link MappingBridge} translates them, and
 * the member names inside them, to whatever the running game calls them.
 */
public final class HookRegistry {

    /** Where in the target method the callback goes. */
    public enum Position {
        /** First instruction. Argument reads are allowed here. */
        HEAD,
        /** Before every return instruction. */
        RETURN,
        /** Before a specific call. */
        BEFORE_INVOKE,
        /** After a specific call. */
        AFTER_INVOKE,
        /**
         * The call is replaced. The callback takes the call's own arguments --
         * which are already on the stack -- and <b>must return void</b>, so its
         * result is discarded. This is {@code @Redirect} on an INVOKE with a
         * void handler.
         */
        REPLACE_INVOKE,
        /**
         * The call is removed and the callback's return value takes its place.
         * The callback takes the call's arguments off the stack and returns
         * that call's own return type.
         */
        REPLACE_INVOKE_WITH,
        /** Before an access to a specific field. */
        BEFORE_FIELD,
        /**
         * The field write is removed. The callback takes the owner and the
         * value -- already on the stack -- and <b>must return void</b>. This is
         * {@code @Redirect} on a PUTFIELD with a void handler.
         */
        REPLACE_FIELD,
        /**
         * The field <i>read</i> is removed and the callback's return value
         * takes its place. The callback takes the owner and returns the field's
         * type. This is {@code @Redirect} on a GETFIELD whose handler returns a
         * value.
         */
        REPLACE_FIELD_WITH,
        /** A constant is left in place and the callback is run just after it. */
        MODIFY_CONSTANT,
        /** A local variable store is left in place and the callback runs just before it. */
        MODIFY_STORE,
        /** Argument N is passed through the callback on the way in. */
        MODIFY_ARGUMENT
    }

    /** How the callback hands a value back. */
    public enum Flow {
        /** Callback returns void. The target is untouched. */
        VOID,
        /**
         * Callback returns boolean. When false the target method stops at the
         * injection point; a reference return becomes null, a primitive one
         * becomes its zero value.
         */
        CANCEL,
        /**
         * Callback returns Object[1]. A null array lets the target run; a
         * non-null one makes the target return element 0 (unboxed when needed).
         * This is how {@code setReturnValue} is spelled here.
         */
        OVERRIDE,
        /**
         * Callback's return value is used in place of whatever the replaced
         * instruction produced, or stored into the modified argument.
         */
        REPLACE
    }

    public static final class Hook {
        /** MCP class the hook goes into, dotted. */
        public final String owner;
        /** MCP method name, without a descriptor. */
        public final String method;
        /** MCP descriptor of the target method, or null to match by name only. */
        public final String descriptor;

        public final Position position;

        /** For the *_INVOKE and *_FIELD positions: the member being looked for. */
        public final String targetOwner;
        public final String targetMember;
        public final String targetDescriptor;
        /** Which occurrence to hit, or -1 for all of them. */
        public final int ordinal;

        /** Internal name of the class holding the callback. */
        public final String callbackOwner;
        public final String callbackName;
        /** Descriptor as written against MCP names; remapped on the way out. */
        public final String callbackDescriptor;

        public final Flow flow;
        /** Slots the callback needs, as "this", "0", "1,2". Empty for none. */
        public final String argSpec;
        /** For MODIFY_ARGUMENT: which argument. */
        public final int argument;
        /** For MODIFY_CONSTANT: the constant to look for. */
        public final String constant;

        /** A required hook that fails to install is reported as a defect. */
        public final boolean required;

        /**
         * Whether a miss on this hook is a defect. Set false for a hook that
         * cannot be confirmed statically -- one that picks an instruction by
         * ordinal, say -- so that the hook report stays a list of real
         * problems.
         */
        public final boolean visible;

        /** True for the positions that consume what the original instruction pushed. */
        public boolean replacesInstruction() {
            return HookRegistry.replacesInstruction(position);
        }

        /** True for the replace positions whose callback supplies the result. */
        public boolean callbackSuppliesResult() {
            return HookRegistry.callbackSuppliesResult(position);
        }

        private Hook(Builder builder) {
            this.owner = builder.owner;
            this.method = builder.method;
            this.descriptor = builder.descriptor;
            this.position = builder.position;
            this.targetOwner = builder.targetOwner;
            this.targetMember = builder.targetMember;
            this.targetDescriptor = builder.targetDescriptor;
            this.ordinal = builder.ordinal;
            this.callbackOwner = builder.callbackOwner;
            this.callbackName = builder.callbackName;
            this.callbackDescriptor = builder.callbackDescriptor;
            this.flow = builder.flow;
            this.argSpec = builder.argSpec;
            this.argument = builder.argument;
            this.constant = builder.constant;
            this.required = builder.required;
            this.visible = builder.visible;
        }

        /** Stable identity for the applied/missing reports. */
        public String key() {
            StringBuilder out = new StringBuilder();
            out.append(owner).append('.').append(method).append('@').append(position);
            if (targetMember != null) {
                out.append(' ').append(targetOwner).append('.').append(targetMember);
            }
            if (ordinal >= 0) {
                out.append('#').append(ordinal);
            }
            return out.toString();
        }
    }

    public static final class Builder {        private final String owner;
        private final String method;
        private String descriptor;
        private Position position = Position.HEAD;
        private String targetOwner;
        private String targetMember;
        private String targetDescriptor;
        private int ordinal = -1;
        private String callbackOwner;
        private String callbackName;
        private String callbackDescriptor = "()V";
        private Flow flow = Flow.VOID;
        private String argSpec = "";
        private int argument;
        private String constant;
        private boolean required = true;
        private boolean visible = true;

        public Builder(String owner, String method) {
            this.owner = owner;
            this.method = method;
        }

        public Builder desc(String value) {
            this.descriptor = value;
            return this;
        }

        public Builder at(Position value) {
            this.position = value;
            return this;
        }

        public Builder of(String ownerName, String member, String memberDescriptor) {
            this.targetOwner = ownerName;
            this.targetMember = member;
            this.targetDescriptor = memberDescriptor;
            return this;
        }

        public Builder ordinal(int value) {
            this.ordinal = value;
            return this;
        }

        public Builder call(String ownerName, String name, String callDescriptor, Flow callFlow) {
            this.callbackOwner = ownerName.replace('.', '/');
            this.callbackName = name;
            this.callbackDescriptor = callDescriptor;
            this.flow = callFlow;
            return this;
        }

        public Builder args(String value) {
            this.argSpec = value == null ? "" : value;
            return this;
        }

        public Builder argument(int value) {
            this.argument = value;
            return this;
        }

        public Builder constant(String value) {
            this.constant = value;
            return this;
        }

        public Builder optional() {
            this.required = false;
            return this;
        }

        /**
         * Marks a hook whose miss should not be reported as a defect. Use it
         * when the injection point is picked by ordinal and can only be
         * confirmed with the game's bytecode in hand.
         */
        public Builder invisible() {
            this.visible = false;
            return this;
        }

        public Hook build() {
            if (callbackOwner == null) {
                throw new IllegalStateException(owner + "." + method + " has no callback");
            }
            switch (position) {
                case BEFORE_INVOKE:
                case AFTER_INVOKE:
                case REPLACE_INVOKE:
                case REPLACE_INVOKE_WITH:
                case BEFORE_FIELD:
                case REPLACE_FIELD:
                case REPLACE_FIELD_WITH:
                    if (targetOwner == null || targetMember == null) {
                        throw new IllegalStateException(owner + "." + method + " at " + position
                                + " needs a target member");
                    }
                    break;
                default:
                    break;
            }
            if (HookRegistry.callbackSuppliesResult(position)
                    && "()V".equals(callbackDescriptor)) {
                throw new IllegalStateException(owner + "." + method + " at " + position
                        + " replaces an instruction with the callback's result, so the callback"
                        + " cannot return void");
            }
            if ((position == Position.REPLACE_INVOKE || position == Position.REPLACE_FIELD)
                    && !"()V".equals(callbackDescriptor)) {
                // Only a problem when the original instruction produced
                // something, which is not known until the target is in hand.
                // HookTransformer.requireVoidCallback enforces it there.
            }
            if (flow == Flow.CANCEL && callbackReturn() != 'Z') {
                throw new IllegalStateException(owner + "." + method
                        + " cancels, so its callback must return boolean, not "
                        + callbackReturn());
            }
            if (flow == Flow.OVERRIDE
                    && !callbackDescriptor.endsWith(")[Ljava/lang/Object;")) {
                throw new IllegalStateException(owner + "." + method
                        + " overrides, so its callback must return Object[] -- one element to"
                        + " force the return value, or null to let the method run");
            }
            return new Hook(this);
        }

        /** The return type character of the callback descriptor. */
        private char callbackReturn() {
            int close = callbackDescriptor.lastIndexOf(')');
            return close < 0 || close + 1 >= callbackDescriptor.length()
                    ? 'V'
                    : callbackDescriptor.charAt(close + 1);
        }
    }

    private static final List<Hook> HOOKS = new ArrayList<Hook>();    private static final Map<String, List<Hook>> BY_OWNER = new LinkedHashMap<String, List<Hook>>();

    private HookRegistry() {
    }

    /** True for the positions that consume what the original instruction pushed. */
    public static boolean replacesInstruction(Position position) {
        return position == Position.REPLACE_INVOKE
                || position == Position.REPLACE_INVOKE_WITH
                || position == Position.REPLACE_FIELD
                || position == Position.REPLACE_FIELD_WITH;
    }

    /** True for the replace positions whose callback supplies the result. */
    public static boolean callbackSuppliesResult(Position position) {
        return position == Position.REPLACE_INVOKE_WITH
                || position == Position.REPLACE_FIELD_WITH;
    }

    public static Builder hook(String mcpOwner, String method) {
        return new Builder(mcpOwner, method);
    }

    public static synchronized void add(Hook hook) {
        HOOKS.add(hook);
        List<Hook> forOwner = BY_OWNER.get(hook.owner);
        if (forOwner == null) {
            forOwner = new ArrayList<Hook>();
            BY_OWNER.put(hook.owner, forOwner);
        }
        forOwner.add(hook);
    }

    public static synchronized void clear() {
        HOOKS.clear();
        BY_OWNER.clear();
    }

    public static synchronized int size() {
        return HOOKS.size();
    }

    /** MCP owners that have at least one hook, in registration order. */
    public static synchronized List<String> owners() {
        return new ArrayList<String>(BY_OWNER.keySet());
    }

    public static synchronized List<Hook> forOwner(String mcpOwner) {
        List<Hook> found = BY_OWNER.get(mcpOwner);
        return found == null ? Collections.<Hook>emptyList() : new ArrayList<Hook>(found);
    }

    public static synchronized List<Hook> all() {
        return new ArrayList<Hook>(HOOKS);
    }
}
