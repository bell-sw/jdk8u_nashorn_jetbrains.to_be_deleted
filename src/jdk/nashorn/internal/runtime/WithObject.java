/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.runtime;

import static jdk.nashorn.internal.runtime.linker.Lookup.MH;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import jdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import org.dynalang.dynalink.CallSiteDescriptor;
import org.dynalang.dynalink.linker.GuardedInvocation;
import org.dynalang.dynalink.support.CallSiteDescriptorFactory;


/**
 * This class supports the handling of scope in a with body.
 *
 */
public class WithObject extends ScriptObject implements Scope {

    private static final MethodHandle WITHEXPRESSIONFILTER = findOwnMH("withFilterExpression", Object.class, Object.class);
    private static final MethodHandle WITHSCOPEFILTER      = findOwnMH("withFilterScope",      Object.class, Object.class);
    private static final MethodHandle BIND_TO_EXPRESSION   = findOwnMH("bindToExpression",     Object.class, Object.class, Object.class);

    /** With expression object. */
    private final Object expression;

    /**
     * Constructor
     *
     * @param scope scope object
     * @param expression with expression
     */
    public WithObject(final ScriptObject scope, final Object expression) {
        super();

        setIsScope();
        setProto(scope);
        this.expression = expression;
    }

    /**
     * Delete a property based on a key.
     * @param key Any valid JavaScript value.
     * @param strict strict mode execution.
     * @return True if deleted.
     */
    @Override
    public boolean delete(final Object key, final boolean strict) {
        if (expression instanceof ScriptObject) {
            final ScriptObject self = (ScriptObject)expression;
            final String propName = ScriptObject.convertKey(key);

            final FindProperty find = self.findProperty(propName, true);

            if (find != null) {
                return self.delete(propName, strict);
            }
        }

        return false;
    }


    @Override
    public GuardedInvocation lookup(final CallSiteDescriptor desc, final boolean megaMorphic) {
        // With scopes can never be observed outside of Nashorn code, so all call sites that can address it will of
        // necessity have a Nashorn descriptor - it is safe to cast.
        final NashornCallSiteDescriptor ndesc = (NashornCallSiteDescriptor)desc;
        FindProperty find = null;
        GuardedInvocation link = null;
        ScriptObject self = null;

        final boolean isNamedOperation;
        final String name;
        if(desc.getNameTokenCount() > 2) {
            isNamedOperation = true;
            name = desc.getNameToken(CallSiteDescriptor.NAME_OPERAND);
        } else {
            isNamedOperation = false;
            name = null;
        }

        if (expression instanceof ScriptObject) {
            self = (ScriptObject)expression;
            if (isNamedOperation) {
                find = self.findProperty(name, true);
            }

            if (find != null) {
                link = self.lookup(desc);

                if (link != null) {
                    return fixExpressionCallSite(ndesc, link);
                }
            }
        }

        final ScriptObject scope = getProto();
        if (isNamedOperation) {
            find = scope.findProperty(name, true);
        }

        if (find != null) {
            return fixScopeCallSite(scope.lookup(desc));
        }

        // the property is not found - now check for
        // __noSuchProperty__ and __noSuchMethod__ in expression
        if (self != null) {
            final String fallBack;

            final String operator = CallSiteDescriptorFactory.tokenizeOperators(desc).get(0);

            switch (operator) {
            case "callMethod":
                throw new AssertionError(); // Nashorn never emits callMethod
            case "getMethod":
                fallBack = NO_SUCH_METHOD_NAME;
                break;
            case "getProp":
            case "getElem":
                fallBack = NO_SUCH_PROPERTY_NAME;
                break;
            default:
                fallBack = null;
                break;
            }

            if (fallBack != null) {
                find = self.findProperty(fallBack, true);
                if (find != null) {
                    switch (operator) {
                    case "getMethod":
                        link = self.noSuchMethod(desc);
                        break;
                    case "getProp":
                    case "getElem":
                        link = self.noSuchProperty(desc);
                        break;
                    default:
                        break;
                    }
                }
            }

            if (link != null) {
                return fixExpressionCallSite(ndesc, link);
            }
        }

        // still not found, may be scope can handle with it's own
        // __noSuchProperty__, __noSuchMethod__ etc.
        link = scope.lookup(desc);

        if (link != null) {
            return fixScopeCallSite(link);
        }

        return null;
    }

    @Override
    public void setSplitState(final int state) {
        getNonWithParent().setSplitState(state);
    }

    @Override
    public int getSplitState() {
        return getNonWithParent().getSplitState();
    }

    /**
     * Get first parent scope that is not an instance of WithObject.
     */
    private Scope getNonWithParent() {
        ScriptObject proto = getProto();

        while (proto != null && proto instanceof WithObject) {
            proto = proto.getProto();
        }

        assert proto instanceof Scope : "with scope without parent scope";
        return (Scope) proto;
    }

    private static GuardedInvocation fixExpressionCallSite(final NashornCallSiteDescriptor desc, final GuardedInvocation link) {
        // If it's not a getMethod, just add an expression filter that converts WithObject in "this" position to its
        // expression.
        if(!"getMethod".equals(desc.getFirstOperator())) {
            return link.filterArguments(0, WITHEXPRESSIONFILTER);
        }

        return link.replaceMethods(
                // Make sure getMethod will bind the script functions it receives to WithObject.expression
                MH.foldArguments(BIND_TO_EXPRESSION, filter(link.getInvocation(), WITHEXPRESSIONFILTER)),
                // No clever things for the guard -- it is still identically filtered.
                filterGuard(link, WITHEXPRESSIONFILTER));
    }

    private static GuardedInvocation fixScopeCallSite(final GuardedInvocation link) {
        return link.replaceMethods(filter(link.getInvocation(), WITHSCOPEFILTER), filterGuard(link, WITHSCOPEFILTER));
    }

    private static MethodHandle filterGuard(final GuardedInvocation link, final MethodHandle filter) {
        final MethodHandle test = link.getGuard();
        return test == null ? null : filter(test, filter);
    }

    private static MethodHandle filter(final MethodHandle mh, final MethodHandle filter) {
        return MH.filterArguments(mh, 0, filter);
    }

    /**
     * Drops the WithObject wrapper from the expression.
     * @param receiver WithObject wrapper.
     * @return The with expression.
     */
    public static Object withFilterExpression(final Object receiver) {
        return ((WithObject)receiver).expression;
    }


    @SuppressWarnings("unused")
    private static Object bindToExpression(final Object fn, final Object receiver) {
        return fn instanceof ScriptFunction ? ((ScriptFunction) fn).makeBoundFunction(withFilterExpression(receiver), new Object[0]) : fn;
    }

    /**
     * Drops the WithObject wrapper from the scope.
     * @param receiver WithObject wrapper.
     * @return The with scope.
     */
    public static Object withFilterScope(final Object receiver) {
        return ((WithObject)receiver).getProto();
    }

    /**
     * Get the with expression for this {@code WithObject}
     * @return the with expression
     */
    public Object getExpression() {
        return expression;
    }

    private static MethodHandle findOwnMH(final String name, final Class<?> rtype, final Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), WithObject.class, name, MH.type(rtype, types));
    }
}