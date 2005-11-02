/*
 * $Id$
 *
 * Copyright 2005 (C) Guillaume Laforge. All Rights Reserved.
 *
 * Redistribution and use of this software and associated documentation
 * ("Software"), with or without modification, are permitted provided that the
 * following conditions are met:
 *  1. Redistributions of source code must retain copyright statements and
 * notices. Redistributions must also contain a copy of this document.
 *  2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *  3. The name "groovy" must not be used to endorse or promote products
 * derived from this Software without prior written permission of The Codehaus.
 * For written permission, please contact info@codehaus.org.
 *  4. Products derived from this Software may not be called "groovy" nor may
 * "groovy" appear in their names without prior written permission of The
 * Codehaus. "groovy" is a registered trademark of The Codehaus.
 *  5. Due credit should be given to The Codehaus - http://groovy.codehaus.org/
 *
 * THIS SOFTWARE IS PROVIDED BY THE CODEHAUS AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE CODEHAUS OR ITS CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 *
 */
package org.codehaus.groovy.scriptom;

import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.DispatchEvents;
import groovy.lang.Closure;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.GroovyShell;
import groovy.lang.Binding;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;

import org.codehaus.groovy.control.CompilationFailedException;

/**
 * Provides a hooking mechanism to use an "events" property belonging to the ActiveXProxy,
 * containing closures for the event handling.
 * <p/>
 * This "events" is backed by a Map that contains keys representing the event to subscribe to,
 * and closures representing the code to execute when the event is triggered.
 * <p/>
 * Jacob allows only to pass to the <code>DispatchEvents</code> class a class of the form:
 * <pre>
 * public class MyEvents {
 *    public void Quit(Variant[] variants) { }
 * }
 * </pre>
 * <p/>
 * To circumvent this, and to allow the users to use closures for event handling,
 * I'm building with ASM an interface, with event methods forged after the name of the keys in the map.
 * I'm then creating a <code>java.lang.reflect.Proxy</code> that delegates all calls to the Proxy
 * to my own <code>InvocationHandler</code> which is implemented by <code>EventSupport</code>.
 * All invocations gets then routed to the relevant closure in the <code>eventHandlers</code> Map.
 *
 * @author Guillaume Laforge
 */
public class EventSupport extends GroovyObjectSupport implements InvocationHandler
{
    private Map eventHandlers = new HashMap();
    private ActiveXComponent activex;

    /**
     * In the constructor, we pass the reference to the <code>ActiveXComponent</code>.
     *
     * @param activex the component
     */
    EventSupport(ActiveXComponent activex)
    {
        this.activex = activex;
    }

    /**
     * Invokes directly a closure in the <code>eventHandlers</code> Map,
     * or call the <code>listen()</code> pseudo-method that triggers the creation of the <code>EventHandler</code>
     * and registers it with <code>DispatchEvents</code>.
     *
     * @param name name of the closure to call, or the "listen" pseudo-method.
     * @param args arguments to be passed to the closure
     * @return result returned by the closre
     */
    public Object invokeMethod(String name, Object args)
    {
        if ("listen".equals(name))
        {
            try {
                StringBuffer methods = new StringBuffer();
                for (Iterator iterator = eventHandlers.keySet().iterator(); iterator.hasNext();) {
                    String eventName = (String) iterator.next();
                    methods.append("    void ")
                    .append(eventName)
                    .append("(Variant[] variants) {\n")
                    .append("        evtHandlers['")
                    .append(eventName)
                    .append("'].call(variants)\n    }\n");
                }

                StringBuffer classSource = new StringBuffer();
                classSource.append("import com.jacob.com.*\n")
                .append("class EventHandler {\n")
                .append("    def evtHandlers\n")
                .append("    EventHandler(scriptBinding) {\n")
                .append("        evtHandlers = scriptBinding\n")
                .append("    }\n")
                .append(methods.toString())
                .append("}\n")
                .append("new EventHandler(binding)\n");

                Map eventHandlersContainer = new HashMap();
                eventHandlersContainer.put("eventHandlers", eventHandlers);
                Binding binding = new Binding(eventHandlers);
                Object generatedInstance = new GroovyShell(binding).evaluate(classSource.toString());

                new DispatchEvents(this.activex, generatedInstance);
            } catch (CompilationFailedException e) {
                e.printStackTrace();
            }
            return null;

        }
        else
        {
            // call the closure from the eventHandlers Map
            return ((Closure) eventHandlers.get(name)).call(args);
        }
    }

    /**
     * Invocation method of the <code>InvocationHandler</code> passed to the Proxy.
     *
     * @param proxy the proxy
     * @param method the name of the method to invoke
     * @param args the arguments to pass to the method
     * @return the return of the method
     * @throws Throwable thrown if the invocation fails
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        // delegates to the GroovyObjectSupport metamethod
        return invokeMethod(method.getName(), args);
    }

    /**
     * Sets the property only if a <code>Closure</code> for event handling is passed as value.
     * The name of the property represents the name of the events triggered by the ActiveX/COM component.
     * The closure is the code to be executed upon the event being triggered.
     *
     * @param property the name of the event
     * @param newValue the closure to execute
     */
    public void setProperty(String property, Object newValue)
    {
        if (newValue instanceof Closure)
            eventHandlers.put(property, newValue);
    }

    /**
     * Retrieves the closure associated with a given event.
     *
     * @param property the name of the event
     * @return the closure associated with the handling of the given event
     */
    public Object getProperty(String property)
    {
        return eventHandlers.get(property);
    }
}
