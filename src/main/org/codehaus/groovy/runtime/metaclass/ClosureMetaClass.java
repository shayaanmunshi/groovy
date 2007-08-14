package org.codehaus.groovy.runtime.metaclass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.Reflector;
import org.codehaus.groovy.runtime.wrappers.Wrapper;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.GroovySystem;
import groovy.lang.MetaBeanProperty;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaClassRegistry;
import groovy.lang.MetaFieldProperty;
import groovy.lang.MetaMethod;
import groovy.lang.MetaProperty;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;

public class ClosureMetaClass extends MetaClassImpl {
    private boolean initialized;
    private Reflector reflector;
    private List closureMethods = new ArrayList(3);
    private Map attributes = new HashMap();
    private MethodChooser chooser;
    private volatile boolean attributeInitDone = false;
    
    private static final MetaClassImpl CLOSURE_METACLASS;
    private static MetaClassImpl CLASS_METACLASS; 
    private static final Object[] EMPTY_ARGUMENTS = {};
    private static final String CLOSURE_CALL_METHOD = "call";
    private static final String CLOSURE_DO_CALL_METHOD = "doCall";
    private static final String CLOSURE_CURRY_METHOD = "curry";
    
    static {
        CLOSURE_METACLASS = new MetaClassImpl(Closure.class);
        CLOSURE_METACLASS.initialize();        
    }

    private synchronized static MetaClass getStaticMetaClass() {
        if (CLASS_METACLASS==null) {
            CLASS_METACLASS = new MetaClassImpl(Class.class);
            CLASS_METACLASS.initialize();
        }
        return CLASS_METACLASS;
    }
    
    private static interface MethodChooser{
        public Object chooseMethod(Class[] arguments, boolean coerce);
    }
    
    private static class StandardClosureChooser implements MethodChooser {
        final private MetaMethod doCall0;
        final private MetaMethod doCall1;
        StandardClosureChooser(MetaMethod m0, MetaMethod m1){
            doCall0 = m0; doCall1 = m1;
        }
        public Object chooseMethod(Class[] arguments, boolean coerce) {
            if (arguments.length==0) return doCall0;
            if (arguments.length==1) return doCall1;
            return null;
        }
    }
    
    private static class NormalMethodChooser implements MethodChooser {
        final private List methods;
        final Class theClass;
        NormalMethodChooser(Class theClass, List methods) {
            this.theClass = theClass;
            this.methods = methods;
        }
        public Object chooseMethod(Class[] arguments, boolean coerce) {
            if (arguments.length == 0) {
                return MetaClassHelper.chooseEmptyMethodParams(methods);
            }
            else if (arguments.length == 1 && arguments[0] == null) {
                return MetaClassHelper.chooseMostGeneralMethodWith1NullParam(methods);
            }
            else {
                List matchingMethods = new ArrayList();

                for (Iterator iter = methods.iterator(); iter.hasNext();) {
                    Object method = iter.next();

                    // making this false helps find matches
                    if (MetaClassHelper.isValidMethod(method, arguments, coerce)) {
                        matchingMethods.add(method);
                    }
                }
                if (matchingMethods.isEmpty()) {
                    return null;
                }
                else if (matchingMethods.size() == 1) {
                    return matchingMethods.get(0);
                }
                return chooseMostSpecificParams(CLOSURE_DO_CALL_METHOD, matchingMethods, arguments);
            }
        }
        private Object chooseMostSpecificParams(String name, List matchingMethods, Class[] arguments) {
            long matchesDistance = -1;
            LinkedList matches = new LinkedList();
            for (Iterator iter = matchingMethods.iterator(); iter.hasNext();) {
                Object method = iter.next();
                Class[] paramTypes = MetaClassHelper.getParameterTypes(method);
                if (!MetaClassHelper.parametersAreCompatible(arguments, paramTypes)) continue;
                long dist = MetaClassHelper.calculateParameterDistance(arguments, paramTypes);
                if (dist==0) return method;
                if (matches.size()==0) {
                    matches.add(method);
                    matchesDistance = dist;
                } else if (dist<matchesDistance) {
                    matchesDistance=dist;
                    matches.clear();
                    matches.add(method);
                } else if (dist==matchesDistance) {
                    matches.add(method);
                }

            }
            if (matches.size()==1) {
                return matches.getFirst();
            }
            if (matches.size()==0) {
                return null;
            }

            //more than one matching method found --> ambigous!
            String msg = "Ambiguous method overloading for method ";
            msg+= theClass.getName()+"#"+name;
            msg+= ".\nCannot resolve which method to invoke for ";
            msg+= InvokerHelper.toString(arguments);
            msg+= " due to overlapping prototypes between:";
            for (Iterator iter = matches.iterator(); iter.hasNext();) {
                Class[] types=MetaClassHelper.getParameterTypes(iter.next());
                msg+= "\n\t"+InvokerHelper.toString(types);
            }
            throw new GroovyRuntimeException(msg);
        }
    }
    
    
    public ClosureMetaClass(MetaClassRegistry registry, Class theClass) {
        super(registry,theClass);
    }
    
    public MetaProperty getMetaProperty(String name) {
        return CLOSURE_METACLASS.getMetaProperty(name);
    }

    private void unwrap(Object[] arguments) {
        for (int i = 0; i != arguments.length; i++) {
            if (arguments[i] instanceof Wrapper) {
                arguments[i] = ((Wrapper)arguments[i]).unwrap();
            }
        }       
    }
    
    private MetaMethod pickClosureMethod(Class[] argClasses) {
        Object answer = chooser.chooseMethod(argClasses, false);
        return (MetaMethod) answer;
    }
    
    private MetaMethod getDelegateMethod(Closure closure, Object delegate, String methodName, Class[] argClasses){
        if (delegate==closure || delegate==null) return null;
        MetaClass delegateMetaClass = lookupObjectMetaClass(delegate);
        return delegateMetaClass.pickMethod(methodName,argClasses);
    }
    
    public Object invokeMethod(Class sender, Object object, String methodName, Object[] originalArguments, boolean isCallToSuper, boolean fromInsideClass) {
        checkInitalised();
        if (object == null) {
            throw new NullPointerException("Cannot invoke method: " + methodName + " on null object");
        }              
        if (log.isLoggable(Level.FINER)){
            MetaClassHelper.logMethodCall(object, methodName, originalArguments);
        }
        
        final Object[] arguments = originalArguments == null ? EMPTY_ARGUMENTS : originalArguments;
        final Class[] argClasses = MetaClassHelper.convertToTypeArray(arguments);
        unwrap(arguments);

        MetaMethod method=null;
        final Closure closure = (Closure) object;
        if (CLOSURE_DO_CALL_METHOD.equals(methodName) || CLOSURE_CALL_METHOD.equals(methodName)) {
            method = pickClosureMethod(argClasses);
            if (method!=null) return MetaClassHelper.doMethodInvoke(object, method, arguments);
        } else if (CLOSURE_CURRY_METHOD.equals(methodName)) {
            return closure.curry(arguments);
        } else {
            method = CLOSURE_METACLASS.pickMethod(methodName, argClasses);
        }
        
        MissingMethodException last = null;
        Object callObject = object;
        if (method==null) {
            final Object owner = closure.getOwner();
            final Object delegate = closure.getDelegate();
            final int resolveStrategy = closure.getResolveStrategy();
            boolean invokeOnDelegate = false;
            boolean invokeOnOwner = false;
            boolean ownerFirst = true;
            
            switch(resolveStrategy) {
                case Closure.TO_SELF:
                break;
                case Closure.DELEGATE_ONLY:
                    method = getDelegateMethod(closure,delegate,methodName,argClasses);
                    callObject = delegate;
                    if (method==null) {
                        invokeOnDelegate = delegate!=closure && (delegate instanceof GroovyObject);
                    }
                break;
                case Closure.OWNER_ONLY:
                    method = getDelegateMethod(closure,owner,methodName,argClasses);
                    callObject = owner;
                    if (method==null) {
                        invokeOnOwner = owner!=closure && (owner instanceof GroovyObject);
                    }
                   
                break;
                case Closure.DELEGATE_FIRST:
                    method = getDelegateMethod(closure,delegate,methodName,argClasses);
                    callObject = delegate;
                    if (method==null) {
                        method = getDelegateMethod(closure,owner,methodName,argClasses);
                        callObject = owner;
                    }
                    if (method==null) {
                        invokeOnDelegate = delegate!=closure && (delegate instanceof GroovyObject);
                        invokeOnOwner    = owner!=closure    && (owner instanceof GroovyObject);
                        ownerFirst = false;
                    }
                break;
                default: // owner first
                    method = getDelegateMethod(closure,owner,methodName,argClasses);
                    callObject = owner;
                    if (method==null){
                        method = getDelegateMethod(closure,delegate,methodName,argClasses);
                        callObject = delegate;
                    }
                    if (method==null) {
                        invokeOnDelegate = delegate!=closure && (delegate instanceof GroovyObject);
                        invokeOnOwner    = owner!=closure    && (owner instanceof GroovyObject);
                    }
            }
            if (method == null) {
                try {
                    if (ownerFirst) {
                        return invokeOnDelegationObjects(invokeOnOwner, owner, invokeOnDelegate, delegate, methodName, arguments);
                    } else {
                        return invokeOnDelegationObjects(invokeOnDelegate, delegate, invokeOnOwner, owner, methodName, arguments);
                    }
                } catch (MissingMethodException mme) {
                    last = mme;
                }
            }
        }
        
        if (method != null) {
            return MetaClassHelper.doMethodInvoke(callObject, method, arguments);
        } else {
            // if no method was found, try to find a closure defined as a field of the class and run it
            Object value = null;
            try {
                value = this.getProperty(object, methodName);
            } catch (MissingPropertyException mpe) {
                // ignore
            }
            if (value instanceof Closure) {  // This test ensures that value != this If you ever change this ensure that value != this
                Closure cl = (Closure) value;
                MetaClass delegateMetaClass = cl.getMetaClass();
                return delegateMetaClass.invokeMethod(cl.getClass(),closure, CLOSURE_DO_CALL_METHOD,originalArguments,false,fromInsideClass);
            }
        }

        throw last;
    }
    
    private Object invokeOnDelegationObjects(
            boolean invoke1, Object o1,
            boolean invoke2, Object o2, 
            String methodName, Object[] args) 
    {
        MissingMethodException first = null;
        if (invoke1) {
            GroovyObject go = (GroovyObject) o1;
            try {
                return go.invokeMethod(methodName, args);
            } catch (MissingMethodException mme) {
                first = mme;
            }
        }
        if (invoke2) {
            GroovyObject go = (GroovyObject) o2;
            try {
                return go.invokeMethod(methodName, args);
            } catch (MissingMethodException mme) {
                if (first==null) first = mme;
            }
        }
        throw first;
    }
    
    private synchronized void initAttributes() {
        if (attributes.size()>0) return;
        attributes.put("!", null); // just a dummy for later
        Field[] fieldArray = (Field[]) AccessController.doPrivileged(new  PrivilegedAction() {
            public Object run() {
                return theClass.getDeclaredFields();
            }
        });
        for (int i = 0; i < fieldArray.length; i++) {
            MetaFieldProperty mfp = new MetaFieldProperty(fieldArray[i]);
            attributes.put(fieldArray[i].getName(), mfp);
        }        
        attributeInitDone = attributes.size()!=0;
    }

    public synchronized void initialize() {
        if (!isInitialized()) {
            Method[] methodArray = (Method[]) AccessController.doPrivileged(new  PrivilegedAction() {
                public Object run() {
                    return theClass.getDeclaredMethods();
                }
            });
            
            for (int i = 0; i < methodArray.length; i++) {
                Method reflectionMethod = methodArray[i];
                if (!reflectionMethod.getName().equals(CLOSURE_DO_CALL_METHOD)) continue;
                MetaMethod method = createMetaMethod(reflectionMethod);
                closureMethods.add(method);
            }
            
            assignMethodChooser();
            
            initialized = true;
        }
        if (reflector == null) {
            generateReflector();
        }
    }
    
    private void assignMethodChooser() {
        if (closureMethods.size()==1) {
            final MetaMethod doCall = (MetaMethod) closureMethods.get(0);
            final Class[] c = doCall.getParameterTypes();
            int length = c.length;
            if (length==0) {
                // no arg method
                chooser = new MethodChooser() {
                    public Object chooseMethod(Class[] arguments, boolean coerce) {
                        if (arguments.length==0) return doCall;
                        return null;
                    }
                };
            } else if (length==1 && c[0]==Object.class) {
                // Object fits all, so simple dispatch rule here
                chooser = new MethodChooser() {
                    public Object chooseMethod(Class[] arguments, boolean coerce) {
                        // <2, because foo() is same as foo(null)
                        if (arguments.length<2) return doCall;
                        return null;
                    }
                };
            } else {
                boolean allObject=true;
                for (int i = 0; i < c.length-1; i++) {
                    if (c[i]!=Object.class) {
                        allObject=false;
                        break;
                    }
                }
                if (allObject && c[c.length-1]==Object.class) {
                    // all arguments are object, so test only if argument number is correct
                    chooser = new MethodChooser() {
                        public Object chooseMethod(Class[] arguments, boolean coerce) {
                            if (arguments.length==c.length) return doCall;
                            return null;
                        }
                    };
                } else if (allObject && c[c.length-1]==Object[].class) {
                    // all arguments are Object but last, which is a vargs argument, that 
                    // will fit all, so jsut test if the number of argument is equal or
                    // more than the parameters we have.
                    final int minimumLength = c.length-2;
                    chooser = new MethodChooser() {
                        public Object chooseMethod(Class[] arguments, boolean coerce) {
                            if (arguments.length>minimumLength) return doCall;
                            return null;
                        }
                    };
                } else {
                    // general case for single method
                    chooser = new MethodChooser() {
                        public Object chooseMethod(Class[] arguments, boolean coerce) {
                            if (MetaClassHelper.isValidMethod(doCall, arguments, coerce)) {
                                return doCall;
                            }
                            return null;
                        }
                    };
                }
            }
        } else if (closureMethods.size()==2) {
            MetaMethod m0=null, m1=null;
            for (Iterator iterator = closureMethods.iterator(); iterator.hasNext();) {
                MetaMethod m = (MetaMethod) iterator.next();
                Class[] c = m.getParameterTypes();
                if (c.length==0) {
                    m0 = m;
                } else if (c.length==1 && c[0]==Object.class) {
                    m1 = m;
                }                    
            }
            if (m0!=null && m1!=null) {
                // standard closure (2 methods because "it" is with default null)
                chooser = new StandardClosureChooser(m0,m1);
            }
        }
        if (chooser==null) {
            // standard chooser for cases if it is not a single method and if it is
            // not the standard closure.
            chooser = new NormalMethodChooser(theClass,closureMethods);
        }
    }
    
    private MetaMethod createMetaMethod(final Method method) {
        if (((MetaClassRegistryImpl)registry).useAccessible()) {
            AccessController.doPrivileged(new PrivilegedAction() {
                public Object run() {
                    method.setAccessible(true);
                    return null;
                }
            });
        }
        if (GroovySystem.isUseReflection()) return new ReflectionMetaMethod(method);
        return new MetaMethod(method);
    }
    
    private void generateReflector() {
        reflector = ((MetaClassRegistryImpl)registry).loadReflector(theClass, closureMethods);
        if (reflector == null) {
            throw new RuntimeException("Should have a reflector for "+theClass.getName());
        }
        // lets set the reflector on all the methods
        for (Iterator iter = closureMethods.iterator(); iter.hasNext();) {
            MetaMethod metaMethod = (MetaMethod) iter.next();
            metaMethod.setReflector(reflector);
        }
    }
    
    private MetaClass lookupObjectMetaClass(Object object) {
        if (object instanceof GroovyObject) {
            GroovyObject go = (GroovyObject) object;
            return go.getMetaClass();
        }
        Class ownerClass = object.getClass();
        if (ownerClass==Class.class) ownerClass = (Class) object;
        MetaClass metaClass = registry.getMetaClass(ownerClass);
        return metaClass;
    }
    
    public List getMethods() {
        List answer = CLOSURE_METACLASS.getMetaMethods();
        answer.addAll(closureMethods);
        return answer;
    }

    public List getMetaMethods() {
        return CLOSURE_METACLASS.getMetaMethods();
    }
    
    public List getProperties() {
        return CLOSURE_METACLASS.getProperties();
    }

    public MetaMethod pickMethod(String name, Class[] argTypes) {
        if (argTypes==null) argTypes = new Class[0];
        if (name.equals(CLOSURE_CALL_METHOD) || name.equals(CLOSURE_DO_CALL_METHOD)) {
            return pickClosureMethod(argTypes);
        }
        return CLOSURE_METACLASS.getMetaMethod(name, argTypes);
    }

    public MetaMethod retrieveStaticMethod(String methodName, Class[] arguments) {
        return null;
    }

    protected boolean isInitialized(){
        return initialized;
    }
    
    public MetaMethod getStaticMetaMethod(String name, Object[] args) {
        return CLOSURE_METACLASS.getStaticMetaMethod(name, args);
    }
    
    public MetaMethod getStaticMetaMethod(String name, Class[] argTypes) {
        return CLOSURE_METACLASS.getStaticMetaMethod(name, argTypes);
    }
    
    public Object getProperty(Class sender, Object object, String name, boolean useSuper, boolean fromInsideClass) {
        if (object instanceof Class) {
            return getStaticMetaClass().getProperty(sender, object, name, useSuper, fromInsideClass);
        } else {
            return CLOSURE_METACLASS.getProperty(sender, object, name, useSuper, fromInsideClass);
        }
    }
    
    public Object getAttribute(Class sender, Object object, String attribute, boolean useSuper, boolean fromInsideClass) {
        if (object instanceof Class) {
            return getStaticMetaClass().getAttribute(sender, object, attribute, useSuper);
        } else {
            if (!attributeInitDone) initAttributes();
            MetaFieldProperty mfp = (MetaFieldProperty) attributes.get(attribute);
            if (mfp==null) {
                return CLOSURE_METACLASS.getAttribute(sender, object, attribute, useSuper);
            } else {
                return mfp.getProperty(object);
            }
        }
    }
    
    public void setAttribute(Class sender, Object object, String attribute,
            Object newValue, boolean useSuper, boolean fromInsideClass) {
        if (object instanceof Class) {
            getStaticMetaClass().setAttribute(sender, object, attribute, newValue, useSuper, fromInsideClass);
        } else {
            if (!attributeInitDone) initAttributes();
            MetaFieldProperty mfp = (MetaFieldProperty) attributes.get(attribute);
            if (mfp==null) {
                CLOSURE_METACLASS.setAttribute(sender, object, attribute, newValue, useSuper, fromInsideClass);
            } else {
                mfp.setProperty(object, newValue);
            }
        }
    }
    
    public Object invokeStaticMethod(Object object, String methodName, Object[] arguments) {
        return getStaticMetaClass().invokeMethod(Class.class, object, methodName, arguments, false, false);
    }
    
    public void setProperty(Class sender, Object object, String name, Object newValue, boolean useSuper, boolean fromInsideClass) {
        if (object instanceof Class) {
            getStaticMetaClass().setProperty(sender, object, name, newValue,useSuper,fromInsideClass);
        } else {
            CLOSURE_METACLASS.setProperty(sender, object, name, newValue,useSuper,fromInsideClass); 
        }
    }

    public MetaMethod getMethodWithoutCaching(Class sender, String methodName, Class[] arguments, boolean isCallToSuper) {
        throw new UnsupportedOperationException();
    }
    
    public void setProperties(Object bean, Map map) {
        throw new UnsupportedOperationException();
    }
    
    private Object invokeConstructor(Class at, Object[] arguments) {
        throw new UnsupportedOperationException();
    }
    
    public void addMetaBeanProperty(MetaBeanProperty mp) {
        throw new UnsupportedOperationException();
    }
    
    public void addMetaMethod(MetaMethod method) {
        throw new UnsupportedOperationException();
    }
    
    public void addNewInstanceMethod(Method method) {
        throw new UnsupportedOperationException();
    }

    public void addNewStaticMethod(Method method) {
        throw new UnsupportedOperationException();
    }
    
    public Constructor retrieveConstructor(Class[] arguments) {
        throw new UnsupportedOperationException();
    }
}
