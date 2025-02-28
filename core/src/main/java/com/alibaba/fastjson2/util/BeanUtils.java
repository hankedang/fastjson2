package com.alibaba.fastjson2.util;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.codec.BeanInfo;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * @author Bob Lee
 * @author Jesse Wilson
 * @author Shaojin Wen
 */
public abstract class BeanUtils {
    static final Type[] EMPTY_TYPE_ARRAY = new Type[]{};

    static ConcurrentMap<Class, Field[]> fieldCache = new ConcurrentHashMap<>();
    static ConcurrentMap<Class, Map<String, Field>> fieldMapCache = new ConcurrentHashMap<>();
    static ConcurrentMap<Class, Field[]> declaredFieldCache = new ConcurrentHashMap<>();
    static ConcurrentMap<Class, Method[]> methodCache = new ConcurrentHashMap<>();
    static ConcurrentMap<Class, Constructor[]> constructorCache = new ConcurrentHashMap<>();

    private static volatile Class RECORD_CLASS;
    private static volatile Method RECORD_GET_RECORD_COMPONENTS;
    private static volatile Method RECORD_COMPONENT_GET_NAME;

    public static String[] getRecordFieldNames(Class<?> recordType) {
        if (JDKUtils.JVM_VERSION < 14) {
            return new String[0];
        }

        try {
            if (RECORD_GET_RECORD_COMPONENTS == null) {
                RECORD_GET_RECORD_COMPONENTS = Class.class.getMethod("getRecordComponents");
            }

            if (RECORD_COMPONENT_GET_NAME == null) {
                Class<?> c = Class.forName("java.lang.reflect.RecordComponent");
                RECORD_COMPONENT_GET_NAME = c.getMethod("getName");
            }

            final Object[] components = (Object[]) RECORD_GET_RECORD_COMPONENTS.invoke(recordType);
            final String[] names = new String[components.length];
            for (int i = 0; i < components.length; i++) {
                names[i] = (String) RECORD_COMPONENT_GET_NAME.invoke(components[i]);
            }

            return names;
        } catch (Exception e) {
            throw new RuntimeException(String.format(
                    "Failed to access Methods needed to support `java.lang.Record`: (%s) %s",
                    e.getClass().getName(), e.getMessage()), e);
        }
    }

    public static void getKotlinConstructor(Class objectClass, BeanInfo beanInfo) {
        Constructor[] constructors = constructorCache.get(objectClass);
        if (constructors == null) {
            constructors = objectClass.getDeclaredConstructors();
            constructorCache.putIfAbsent(objectClass, constructors);
        }

        Constructor creatorConstructor = null;
        String[] paramNames = beanInfo.createParameterNames;

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (paramNames != null && parameterTypes.length != paramNames.length) {
                continue;
            }

            if (parameterTypes.length > 0 && "kotlin.jvm.internal.DefaultConstructorMarker".equals(parameterTypes[parameterTypes.length - 1].getName())) {
                beanInfo.markerConstructor = constructor;
                continue;
            }

            if (creatorConstructor != null && creatorConstructor.getParameterTypes().length >= parameterTypes.length) {
                continue;
            }

            creatorConstructor = constructor;
        }

        beanInfo.creatorConstructor = creatorConstructor;
    }

    private static volatile boolean kotlinClassKlassError;
    private static volatile Constructor kotlinKclassConstructor;
    private static volatile Method kotlinKclassGetConstructors;
    private static volatile Method kotlinKfunctionGetParameters;
    private static volatile Method kotlinKparameterGetName;
    private static volatile boolean kotlinError;

    public static String[] getKotlinConstructorParameters(Class clazz) {
        if (kotlinKclassConstructor == null && !kotlinClassKlassError) {
            try {
                Class classKotlinKclass = Class.forName("kotlin.reflect.jvm.internal.KClassImpl");
                kotlinKclassConstructor = classKotlinKclass.getConstructor(Class.class);
            } catch (Throwable e) {
                kotlinClassKlassError = true;
            }
        }
        if (kotlinKclassConstructor == null) {
            return null;
        }

        if (kotlinKclassGetConstructors == null && !kotlinClassKlassError) {
            try {
                Class classKotlinKclass = Class.forName("kotlin.reflect.jvm.internal.KClassImpl");
                kotlinKclassGetConstructors = classKotlinKclass.getMethod("getConstructors");
            } catch (Throwable e) {
                kotlinClassKlassError = true;
            }
        }

        if (kotlinKfunctionGetParameters == null && !kotlinClassKlassError) {
            try {
                Class classKotlinKfunction = Class.forName("kotlin.reflect.KFunction");
                kotlinKfunctionGetParameters = classKotlinKfunction.getMethod("getParameters");
            } catch (Throwable e) {
                kotlinClassKlassError = true;
            }
        }

        if (kotlinKparameterGetName == null && !kotlinClassKlassError) {
            try {
                Class classKotlinnKparameter = Class.forName("kotlin.reflect.KParameter");
                kotlinKparameterGetName = classKotlinnKparameter.getMethod("getName");
            } catch (Throwable e) {
                kotlinClassKlassError = true;
            }
        }

        if (kotlinError) {
            return null;
        }

        try {
            Object constructor = null;
            Object kclassImpl = kotlinKclassConstructor.newInstance(clazz);
            Iterable it = (Iterable) kotlinKclassGetConstructors.invoke(kclassImpl);
            for (Iterator iterator = it.iterator(); iterator.hasNext(); iterator.hasNext()) {
                Object item = iterator.next();
                List parameters = (List) kotlinKfunctionGetParameters.invoke(item);
                if (constructor != null && parameters.size() == 0) {
                    continue;
                }
                constructor = item;
            }

            if (constructor == null) {
                return null;
            }

            List parameters = (List) kotlinKfunctionGetParameters.invoke(constructor);
            String[] names = new String[parameters.size()];
            for (int i = 0; i < parameters.size(); i++) {
                Object param = parameters.get(i);
                names[i] = (String) kotlinKparameterGetName.invoke(param);
            }
            return names;
        } catch (Throwable e) {
            e.printStackTrace();
            kotlinError = true;
        }
        return null;
    }

    public static void fields(Class objectClass, Consumer<Field> fieldReaders) {
        Field[] fields = fieldCache.get(objectClass);
        if (fields == null) {
            fields = objectClass.getFields();
            fieldCache.putIfAbsent(objectClass, fields);
        }

        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                continue;
            }

            fieldReaders.accept(field);
        }
    }

    public static Field getDeclaredField(Class objectClass, String fieldName) {
        Map<String, Field> fieldMap = fieldMapCache.get(objectClass);
        if (fieldMap == null) {
            Map<String, Field> map = new HashMap<>();
            declaredFields(objectClass, field -> map.put(field.getName(), field));

            fieldMapCache.putIfAbsent(objectClass, map);
            fieldMap = fieldMapCache.get(objectClass);
        }

        return fieldMap.get(fieldName);
    }

    public static void declaredFields(Class objectClass, Consumer<Field> fieldConsumer) {
        Class superclass = objectClass.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            declaredFields(superclass, fieldConsumer);
        }

        Field[] fields = declaredFieldCache.get(objectClass);
        if (fields == null) {
            Field[] declaredFields = objectClass.getDeclaredFields();

            boolean allMatch = true;
            for (Field field : declaredFields) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                fields = declaredFields;
            } else {
                List<Field> list = new ArrayList<>(declaredFields.length);
                for (Field field : declaredFields) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers)) {
                        continue;
                    }
                    list.add(field);
                }
                fields = list.toArray(new Field[list.size()]);
            }

            fieldCache.putIfAbsent(objectClass, fields);
        }

        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                continue;
            }

            fieldConsumer.accept(field);
        }
    }

    public static void staticMethod(Class objectClass, Consumer<Method> methodConsumer) {
        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for (Method method : methods) {
            int modifiers = method.getModifiers();
            if (!Modifier.isStatic(modifiers)) {
                continue;
            }

            methodConsumer.accept(method);
        }
    }

    public static Method buildMethod(Class objectClass, String methodName) {
        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for (Method method : methods) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                continue;
            }

            if (method.getParameterCount() != 0) {
                continue;
            }

            if (method.getName().equals(methodName)) {
                return method;
            }
        }

        return null;
    }

    public static void constructor(Class objectClass, Consumer<Constructor> constructorConsumer) {
        Constructor[] constructors = constructorCache.get(objectClass);
        if (constructors == null) {
            constructors = objectClass.getDeclaredConstructors();
            constructorCache.putIfAbsent(objectClass, constructors);
        }

        for (Constructor constructor : constructors) {
            constructorConsumer.accept(constructor);
        }
    }

    public static Constructor getDefaultConstructor(Class objectClass) {
        if (objectClass == StackTraceElement.class && JDKUtils.JVM_VERSION >= 9) {
            return null;
        }

        Constructor[] constructors = constructorCache.get(objectClass);
        if (constructors == null) {
            constructors = objectClass.getDeclaredConstructors();
            constructorCache.putIfAbsent(objectClass, constructors);
        }

        for (Constructor constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                return constructor;
            }
        }

        return null;
    }

    public static void setters(Class objectClass, Consumer<Method> methodConsumer) {
        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for (Method method : methods) {
            int paramCount = method.getParameterCount();

            // read only getter
            if (paramCount == 0) {
                String methodName = method.getName();
                if (methodName.length() <= 3 || !methodName.startsWith("get")) {
                    continue;
                }

                Class<?> returnType = method.getReturnType();
                if (returnType == AtomicInteger.class
                        || returnType == AtomicLong.class
                        || returnType == AtomicBoolean.class
                        || returnType == AtomicIntegerArray.class
                        || returnType == AtomicLongArray.class
                        || returnType == AtomicReference.class
                        || Collection.class.isAssignableFrom(returnType)
                        || Map.class.isAssignableFrom(returnType)
                ) {
                    methodConsumer.accept(method);
                    continue;
                }
            }

            if (paramCount == 2
                    && method.getReturnType() == Void.TYPE
                    && method.getParameterTypes()[0] == String.class
            ) {
                Annotation[] annotations = method.getAnnotations();

                boolean unwrapped = false;
                for (Annotation annotation : annotations) {
                    Class<? extends Annotation> annotationType = annotation.annotationType();
                    if (annotationType == JSONField.class) {
                        if (((JSONField) annotation).unwrapped()) {
                            unwrapped = true;
                            break;
                        }
                        continue;
                    }

                    switch (annotationType.getName()) {
                        case "com.fasterxml.jackson.annotation.JsonAnySetter":
                            unwrapped = true;
                            break;
                        default:
                            break;
                    }
                }

                if (unwrapped) {
                    methodConsumer.accept(method);
                }
                continue;
            }

            if (paramCount != 1) {
                continue;
            }

            int mods = method.getModifiers();
            if (Modifier.isStatic(mods)) {
                continue;
            }

            String methodName = method.getName();
            final int methodNameLength = methodName.length();
            if (methodNameLength <= 3 || !methodName.startsWith("set")) {
                continue;
            }

            methodConsumer.accept(method);
        }
    }

    public static void setters(Class objectClass, boolean checkPrefix, Consumer<Method> methodConsumer) {
        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for (Method method : methods) {
            int paramType = method.getParameterCount();

            // read only getter
            if (paramType == 0) {
                String methodName = method.getName();
                if (methodName.length() <= 3 || (checkPrefix && !methodName.startsWith("get"))) {
                    continue;
                }

                Class<?> returnType = method.getReturnType();
                if (returnType == AtomicInteger.class
                        || returnType == AtomicLong.class
                        || returnType == AtomicBoolean.class
                        || returnType == AtomicIntegerArray.class
                        || returnType == AtomicLongArray.class
                        || Collection.class.isAssignableFrom(returnType)
                ) {
                    methodConsumer.accept(method);
                    continue;
                }
            }

            if (paramType != 1) {
                continue;
            }

            int mods = method.getModifiers();
            if (Modifier.isStatic(mods)) {
                continue;
            }

            String methodName = method.getName();
            final int methodNameLength = methodName.length();
            if (methodNameLength <= 3 || (checkPrefix && !methodName.startsWith("set"))) {
                continue;
            }

            methodConsumer.accept(method);
        }
    }

    public static void annotationMethods(Class objectClass, Consumer<Method> methodConsumer) {
        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for_:
        for (Method method : methods) {
            if (method.getParameterCount() != 0) {
                continue;
            }

            switch (method.getName()) {
                case "toString":
                case "hashCode":
                case "annotationType":
                case "wait":
                case "notify":
                case "notifyAll":
                case "getClass":
                    continue for_;
                default:
                    break;
            }

            methodConsumer.accept(method);
        }
    }

    public static Member getEnumValueField(Class clazz) {
        if (clazz == null) {
            return null;
        }

        Member member = null;

        Method[] methods = methodCache.get(clazz);
        if (methods == null) {
            methods = clazz.getMethods();
            methodCache.putIfAbsent(clazz, methods);
        }

        for (Method method : methods) {
            if (method.getReturnType() == Void.class) {
                continue;
            }

            Annotation[] annotations = method.getAnnotations();
            for (Annotation annotation : annotations) {
                Class<? extends Annotation> annotationType = annotation.annotationType();
                switch (annotationType.getName()) {
                    case "com.alibaba.fastjson.annotation.JSONField":
                    case "com.alibaba.fastjson2.annotation.JSONField":
                    case "com.fasterxml.jackson.annotation.JsonValue":
                        member = method;
                        break;
                    default:
                        break;
                }
            }
        }

        Field[] fields = fieldCache.get(clazz);
        if (fields == null) {
            fields = clazz.getFields();
            fieldCache.putIfAbsent(clazz, fields);
        }

        for (Field field : fields) {
            Annotation[] annotations = field.getAnnotations();
            for (Annotation annotation : annotations) {
                Class<? extends Annotation> annotationType = annotation.annotationType();
                switch (annotationType.getName()) {
                    case "com.alibaba.fastjson.annotation.JSONField":
                    case "com.alibaba.fastjson.annotation2.JSONField":
                        member = field;
                        break;
                    default:
                        break;
                }
            }
        }

        return member;
    }

    public static void getters(Class objectClass, Consumer<Method> methodConsumer) {
        if (objectClass == null) {
            return;
        }

        if (Proxy.isProxyClass(objectClass)) {
            Class[] interfaces = objectClass.getInterfaces();
            if (interfaces.length == 1) {
                getters(interfaces[0], methodConsumer);
                return;
            }
        }

        if (TypeUtils.isProxy(objectClass)) {
            Class superclass = objectClass.getSuperclass();
            getters(superclass, methodConsumer);
            return;
        }

        boolean record = isRecord(objectClass);

        String[] recordFieldNames = null;
        if (record) {
            recordFieldNames = getRecordFieldNames(objectClass);
        }

        Method[] methods = methodCache.get(objectClass);
        if (methods == null) {
            methods = objectClass.getMethods();
            methodCache.putIfAbsent(objectClass, methods);
        }

        for (Method method : methods) {
            int paramType = method.getParameterCount();
            if (paramType != 0) {
                continue;
            }

            int mods = method.getModifiers();
            if (Modifier.isStatic(mods)) {
                continue;
            }

            Class<?> returnClass = method.getReturnType();
            if (returnClass == Void.class) {
                continue;
            }

            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass == Enum.class) {
                continue;
            }

            String methodName = method.getName();

            if (record) {
                boolean match = false;
                for (String recordFieldName : recordFieldNames) {
                    if (methodName.equals(recordFieldName)) {
                        match = true;
                        break;
                    }
                }

                if (match) {
                    methodConsumer.accept(method);
                    continue;
                }
            }

            final int methodNameLength = methodName.length();

            boolean nameMatch;
            if (returnClass == boolean.class) {
                nameMatch = methodName.startsWith("is") && methodNameLength > 2;
                if (nameMatch) {
                    char firstChar = methodName.charAt(2);
                    if (firstChar >= 'a' && firstChar <= 'z' && methodNameLength == 3) {
                        nameMatch = false;
                    }
                }
            } else {
                nameMatch = methodName.startsWith("get") && methodNameLength > 3;
                if (nameMatch) {
                    char firstChar = methodName.charAt(3);
                    if (firstChar >= 'a' && firstChar <= 'z' && methodNameLength == 4) {
                        nameMatch = false;
                    }
                }
            }

            if (!nameMatch) {
                Annotation[] annotations = method.getAnnotations();
                for (Annotation annotation : annotations) {
                    Class<? extends Annotation> annotationType = annotation.annotationType();
                    String annotationTypeName = annotationType.getName();
                    switch (annotationTypeName) {
                        case "com.alibaba.fastjson.annotation.JSONField":
                        case "com.alibaba.fastjson.annotation2.JSONField":
                        case "com.fasterxml.jackson.annotation.JsonValue":
                        case "com.fasterxml.jackson.annotation.JsonRawValue":
                        case "com.fasterxml.jackson.annotation.JsonProperty":
                            nameMatch = true;
                            break;
                        default:
                            break;
                    }
                }
            }

            if (!nameMatch) {
                continue;
            }

            if (returnClass == Class.class && "getClass".equals(methodName)) {
                continue;
            }

            methodConsumer.accept(method);
        }
    }

    public static boolean isRecord(Class objectClass) {
        Class superclass = objectClass.getSuperclass();
        if (superclass == null) {
            return false;
        }

        if (RECORD_CLASS == null) {
            String superclassName = superclass.getName();
            if ("java.lang.Record".equals(superclassName)) {
                RECORD_CLASS = RECORD_CLASS;
                return true;
            } else {
                return false;
            }
        }

        return superclass == RECORD_CLASS;
    }

    public static String setterName(String methodName, String namingStrategy) {
        if (namingStrategy == null) {
            namingStrategy = "CamelCase";
        }

        int methodNameLength = methodName.length();
        if (methodNameLength <= 3) {
            return methodName;
        }

        int prefixLength = methodName.startsWith("set") ? 3 : 0;

        switch (namingStrategy) {
            case "NeverUseThisValueExceptDefaultValue":
            case "CamelCase": {
                char[] chars = new char[methodNameLength - prefixLength];
                methodName.getChars(prefixLength, methodNameLength, chars, 0);
                char c0 = chars[0];
                boolean c1UCase = chars.length > 1 && chars[1] >= 'A' && chars[1] <= 'Z';
                if (c0 >= 'A' && c0 <= 'Z' && !c1UCase) {
                    chars[0] = (char) (c0 + 32);
                }
                return new String(chars);
            }
            case "PascalCase":
                return pascal(methodName, methodNameLength, prefixLength);
            case "SnakeCase": {
                return snakeCase(methodName, prefixLength);
            }
            case "UpperCaseWithUnderScores": {
                return underScores(methodName, prefixLength, true);
            }
            case "UpperCase": {
                char[] chars = new char[methodNameLength - prefixLength];
                methodName.getChars(prefixLength, methodNameLength, chars, 0);
                char c0 = chars[0];
                for (int i = 0; i < chars.length; i++) {
                    char ch = chars[i];
                    if (ch >= 'a' && c0 <= 'z') {
                        chars[i] = (char) (ch - 32);
                    }
                }
                return new String(chars);
            }
            default:
                throw new JSONException("TODO : " + namingStrategy);
        }
    }

    public static String setterName(String methodName, int prefixLength) {
        int methodNameLength = methodName.length();
        char[] chars = new char[methodNameLength - prefixLength];
        methodName.getChars(prefixLength, methodNameLength, chars, 0);
        char c0 = chars[0];
        boolean c1UCase = chars.length > 1 && chars[1] >= 'A' && chars[1] <= 'Z';
        if (c0 >= 'A' && c0 <= 'Z' && !c1UCase) {
            chars[0] = (char) (c0 + 32);
        }
        return new String(chars);
    }

    public static String getterName(String methodName, String namingStrategy) {
        if (namingStrategy == null) {
            namingStrategy = "CamelCase";
        }

        final int methodNameLength = methodName.length();
        boolean is = methodName.startsWith("is");
        boolean get = methodName.startsWith("get");

        final int prefixLength;
        if (is) {
            prefixLength = 2;
        } else if (get) {
            prefixLength = 3;
        } else {
            prefixLength = 0;
        }

        switch (namingStrategy) {
            case "NeverUseThisValueExceptDefaultValue":
            case "CamelCase": {
                char[] chars = new char[methodNameLength - prefixLength];
                methodName.getChars(prefixLength, methodNameLength, chars, 0);
                char c0 = chars[0];
                boolean c1UCase = chars.length > 1 && chars[1] >= 'A' && chars[1] <= 'Z';
                if (c0 >= 'A' && c0 <= 'Z' && !c1UCase) {
                    chars[0] = (char) (c0 + 32);
                }
                return new String(chars);
            }
            case "PascalCase": {
                return pascal(methodName, methodNameLength, prefixLength);
            }
            case "SnakeCase": {
                return snakeCase(methodName, prefixLength);
            }
            case "UpperCaseWithUnderScores": {
                return underScores(methodName, prefixLength, true);
            }
            case "UpperCase": {
                return methodName.substring(prefixLength).toUpperCase();
            }
            case "KebabCase": {
                StringBuilder buf = new StringBuilder();
                final int firstIndex;
                if (is) {
                    firstIndex = 2;
                } else if (get) {
                    firstIndex = 3;
                } else {
                    firstIndex = 0;
                }

                for (int i = firstIndex; i < methodName.length(); ++i) {
                    char ch = methodName.charAt(i);
                    if (ch >= 'A' && ch <= 'Z') {
                        char chUcase = (char) (ch + 32);
                        if (i > firstIndex) {
                            buf.append('-');
                        }
                        buf.append(chUcase);
                    } else {
                        buf.append(ch);
                    }
                }
                return buf.toString();
            }
            default:
                throw new JSONException("TODO : " + namingStrategy);
        }
    }

    private static String pascal(String methodName, int methodNameLength, int prefixLength) {
        char[] chars = new char[methodNameLength - prefixLength];
        methodName.getChars(prefixLength, methodNameLength, chars, 0);
        char c0 = chars[0];
        if (c0 >= 'a' && c0 <= 'z' && chars.length > 1) {
            boolean c1UCase = chars[1] >= 'a' && chars[1] <= 'z';
            chars[0] = (char) (c0 - 32);
        } else if (c0 == '_' && chars.length > 2) {
            char c1 = chars[1];
            if (c1 >= 'a' && c1 <= 'z' && chars[2] >= 'a' && chars[2] <= 'z') {
                chars[1] = (char) (c1 - 32);
            }
        }
        return new String(chars);
    }

    public static String fieldName(String methodName, String namingStrategy) {
        if (namingStrategy == null) {
            namingStrategy = "CamelCase";
        }

        switch (namingStrategy) {
            case "NeverUseThisValueExceptDefaultValue":
            case "CamelCase": {
                char c0 = methodName.charAt(0);
                char c1 = methodName.length() > 1 ? methodName.charAt(1) : '\0';
                if (c0 >= 'A' && c0 <= 'Z'
                        && methodName.length() > 1
                        && (c1 < 'A' || c1 > 'Z')) {
                    char[] chars = methodName.toCharArray();
                    chars[0] = (char) (c0 + 32);
                    return new String(chars);
                }
                return methodName;
            }
            case "PascalCase": {
                if (methodName.isEmpty()) {
                    return methodName;
                }
                char c0 = methodName.charAt(0);
                char c1;
                if (c0 >= 'a' && c0 <= 'z'
                        && methodName.length() > 1
                        && (c1 = methodName.charAt(1)) >= 'a'
                        && c1 <= 'z') {
                    char[] chars = methodName.toCharArray();
                    chars[0] = (char) (c0 - 32);
                    return new String(chars);
                } else if (c0 == '_'
                        && methodName.length() > 1
                        && (c1 = methodName.charAt(1)) >= 'a'
                        && c1 <= 'z') {
                    char[] chars = methodName.toCharArray();
                    chars[1] = (char) (c1 - 32);
                    return new String(chars);
                }
                return methodName;
            }
            case "SnakeCase":
                return snakeCase(methodName, 0);
            case "UpperCaseWithUnderScores":
                return underScores(methodName, 0, true);
            case "LowerCaseWithUnderScores":
                return underScores(methodName, 0, false);
            case "UpperCaseWithDashes":
                return dashes(methodName, 0, true);
            case "LowerCaseWithDashes":
                return dashes(methodName, 0, false);
            case "UpperCaseWithDots":
                return dots(methodName, 0, true);
            case "LowerCaseWithDots":
                return dots(methodName, 0, false);
            case "UpperCase":
                return methodName.toUpperCase();
            case "UpperCamelCaseWithSpaces":
                return upperCamelWith(methodName, 0, ' ');
            case "UpperCamelCaseWithUnderScores":
                return upperCamelWith(methodName, 0, '_');
            case "UpperCamelCaseWithDashes":
                return upperCamelWith(methodName, 0, '-');
            case "UpperCamelCaseWithDots":
                return upperCamelWith(methodName, 0, '.');
            case "KebabCase": {
                StringBuilder buf = new StringBuilder();
                for (int i = 0; i < methodName.length(); ++i) {
                    char ch = methodName.charAt(i);
                    if (ch >= 'A' && ch <= 'Z') {
                        char chUcase = (char) (ch + 32);
                        if (i > 0) {
                            buf.append('-');
                        }
                        buf.append(chUcase);
                    } else {
                        buf.append(ch);
                    }
                }
                return buf.toString();
            }
            default:
                throw new JSONException("TODO : " + namingStrategy);
        }
    }

    static String snakeCase(String methodName, int prefixLength) {
        final int methodNameLength = methodName.length();

        char[] buf = TypeUtils.CHARS_UPDATER.getAndSet(TypeUtils.CACHE, null);
        if (buf == null) {
            buf = new char[128];
        }
        try {
            int off = 0;
            for (int i = prefixLength; i < methodNameLength; ++i) {
                char ch = methodName.charAt(i);
                if (ch >= 'A' && ch <= 'Z') {
                    char chUcase = (char) (ch + 32);
                    if (i > prefixLength) {
                        buf[off++] = '_';
                    }
                    buf[off++] = chUcase;
                } else {
                    buf[off++] = ch;
                }
            }
            return new String(buf, 0, off);
        } finally {
            TypeUtils.CHARS_UPDATER.set(TypeUtils.CACHE, buf);
        }
    }

    static String upperCamelWith(String methodName, int prefixLength, char separator) {
        final int methodNameLength = methodName.length();

        char[] buf = TypeUtils.CHARS_UPDATER.getAndSet(TypeUtils.CACHE, null);
        if (buf == null) {
            buf = new char[128];
        }
        try {
            int off = 0;
            for (int i = prefixLength; i < methodNameLength; ++i) {
                char ch = methodName.charAt(i);
                char c1;
                if (i == prefixLength) {
                    if (ch >= 'a' && ch <= 'z'
                            && i + 1 < methodNameLength
                            && (c1 = methodName.charAt(i + 1)) >= 'a'
                            && c1 <= 'z') {
                        buf[off++] = (char) (ch - 32);
                    } else if (ch == '_' && i + 1 < methodNameLength
                            && (c1 = methodName.charAt(i + 1)) >= 'a'
                            && c1 <= 'z') {
                        buf[off++] = ch;
                        buf[off++] = (char) (c1 - 32);
                        ++i;
                    } else {
                        buf[off++] = ch;
                    }
                } else if (ch >= 'A' && ch <= 'Z'
                        && i + 1 < methodNameLength
                        && ((c1 = methodName.charAt(i + 1)) < 'A' || c1 > 'Z')) {
                    if (i > prefixLength) {
                        buf[off++] = separator;
                    }
                    buf[off++] = ch;
                } else if (ch >= 'A' && ch <= 'Z'
                        && i > prefixLength
                        && i + 1 < methodNameLength
                        && (c1 = methodName.charAt(i + 1)) >= 'A'
                        && c1 <= 'Z'
                        && (c1 = methodName.charAt(i - 1)) >= 'a'
                        && c1 <= 'z') {
                    if (i > prefixLength) {
                        buf[off++] = separator;
                    }
                    buf[off++] = ch;
                } else {
                    buf[off++] = ch;
                }
            }
            return new String(buf, 0, off);
        } finally {
            TypeUtils.CHARS_UPDATER.set(TypeUtils.CACHE, buf);
        }
    }

    static String underScores(String methodName, int prefixLength, boolean upper) {
        final int methodNameLength = methodName.length();

        char[] buf = TypeUtils.CHARS_UPDATER.getAndSet(TypeUtils.CACHE, null);
        if (buf == null) {
            buf = new char[128];
        }
        try {
            int off = 0;
            for (int i = prefixLength; i < methodNameLength; ++i) {
                char ch = methodName.charAt(i);
                if (upper) {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '_';
                        }
                        buf[off++] = ch;
                    } else {
                        if (ch >= 'a' && ch <= 'z') {
                            ch -= 32;
                        }
                        buf[off++] = ch;
                    }
                } else {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '_';
                        }
                        buf[off++] = (char) (ch + 32);
                    } else {
                        buf[off++] = ch;
                    }
                }
            }
            return new String(buf, 0, off);
        } finally {
            TypeUtils.CHARS_UPDATER.set(TypeUtils.CACHE, buf);
        }
    }

    static String dashes(String methodName, int prefixLength, boolean upper) {
        final int methodNameLength = methodName.length();

        char[] buf = TypeUtils.CHARS_UPDATER.getAndSet(TypeUtils.CACHE, null);
        if (buf == null) {
            buf = new char[128];
        }
        try {
            int off = 0;
            for (int i = prefixLength; i < methodNameLength; ++i) {
                char ch = methodName.charAt(i);
                if (upper) {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '-';
                        }
                        buf[off++] = ch;
                    } else {
                        if (ch >= 'a' && ch <= 'z') {
                            ch -= 32;
                        }
                        buf[off++] = ch;
                    }
                } else {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '-';
                        }
                        buf[off++] = (char) (ch + 32);
                    } else {
                        buf[off++] = ch;
                    }
                }
            }
            return new String(buf, 0, off);
        } finally {
            TypeUtils.CHARS_UPDATER.set(TypeUtils.CACHE, buf);
        }
    }

    static String dots(String methodName, int prefixLength, boolean upper) {
        final int methodNameLength = methodName.length();

        char[] buf = TypeUtils.CHARS_UPDATER.getAndSet(TypeUtils.CACHE, null);
        if (buf == null) {
            buf = new char[128];
        }
        try {
            int off = 0;
            for (int i = prefixLength; i < methodNameLength; ++i) {
                char ch = methodName.charAt(i);
                if (upper) {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '.';
                        }
                        buf[off++] = ch;
                    } else {
                        if (ch >= 'a' && ch <= 'z') {
                            ch -= 32;
                        }
                        buf[off++] = ch;
                    }
                } else {
                    if (ch >= 'A' && ch <= 'Z') {
                        if (i > prefixLength) {
                            buf[off++] = '.';
                        }
                        buf[off++] = (char) (ch + 32);
                    } else {
                        buf[off++] = ch;
                    }
                }
            }
            return new String(buf, 0, off);
        } finally {
            TypeUtils.CHARS_UPDATER.set(TypeUtils.CACHE, buf);
        }
    }

    public static Type getFieldType(TypeReference type, Class<?> raw, Member field, Type fieldType) {
        Class<?> declaringClass = field.getDeclaringClass();

        while (raw != Object.class) {
            if (declaringClass == raw) {
                return resolve(type.getType(), declaringClass, fieldType);
            }
            type = TypeReference.get(resolve(type.getType(), raw, raw.getGenericSuperclass()));
            raw = type.getRawType();
        }
        return null;
    }

    /**
     * Returns a new parameterized type, applying {@code typeArguments} to
     * {@code rawType} and enclosed by {@code ownerType}.
     *
     * @return a {@link java.io.Serializable serializable} parameterized type.
     */
    public static ParameterizedType newParameterizedTypeWithOwner(
            Type ownerType, Type rawType, Type... typeArguments) {
        return new ParameterizedTypeImpl(ownerType, rawType, typeArguments);
    }

    /**
     * Returns an array type whose elements are all instances of
     * {@code componentType}.
     *
     * @return a {@link java.io.Serializable serializable} generic array type.
     */
    public static GenericArrayType arrayOf(Type componentType) {
        return new GenericArrayTypeImpl(componentType);
    }

    /**
     * Returns a type that represents an unknown type that extends {@code bound}.
     * For example, if {@code bound} is {@code CharSequence.class}, this returns
     * {@code ? extends CharSequence}. If {@code bound} is {@code Object.class},
     * this returns {@code ?}, which is shorthand for {@code ? extends Object}.
     */
    public static WildcardType subtypeOf(Type bound) {
        Type[] upperBounds;
        if (bound instanceof WildcardType) {
            upperBounds = ((WildcardType) bound).getUpperBounds();
        } else {
            upperBounds = new Type[]{bound};
        }
        return new WildcardTypeImpl(upperBounds, EMPTY_TYPE_ARRAY);
    }

    /**
     * Returns a type that represents an unknown supertype of {@code bound}. For
     * example, if {@code bound} is {@code String.class}, this returns {@code ?
     * super String}.
     */
    public static WildcardType supertypeOf(Type bound) {
        Type[] lowerBounds;
        if (bound instanceof WildcardType) {
            lowerBounds = ((WildcardType) bound).getLowerBounds();
        } else {
            lowerBounds = new Type[]{bound};
        }
        return new WildcardTypeImpl(new Type[]{Object.class}, lowerBounds);
    }

    /**
     * Returns a type that is functionally equal but not necessarily equal
     * according to {@link Object#equals(Object) Object.equals()}. The returned
     * type is {@link java.io.Serializable}.
     */
    public static Type canonicalize(Type type) {
        if (type instanceof Class) {
            Class<?> c = (Class<?>) type;
            return c.isArray() ? new GenericArrayTypeImpl(canonicalize(c.getComponentType())) : c;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) type;
            return new ParameterizedTypeImpl(p.getOwnerType(),
                    p.getRawType(), p.getActualTypeArguments());
        } else if (type instanceof GenericArrayType) {
            GenericArrayType g = (GenericArrayType) type;
            return new GenericArrayTypeImpl(g.getGenericComponentType());
        } else if (type instanceof WildcardType) {
            WildcardType w = (WildcardType) type;
            return new WildcardTypeImpl(w.getUpperBounds(), w.getLowerBounds());
        } else {
            // type is either serializable as-is or unsupported
            return type;
        }
    }

    public static Class<?> getRawType(Type type) {
        if (type instanceof Class<?>) {
            // type is a normal class.
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;

            // I'm not exactly sure why getRawType() returns Type instead of Class.
            // Neal isn't either but suspects some pathological case related
            // to nested classes exists.
            Type rawType = parameterizedType.getRawType();
            checkArgument(rawType instanceof Class);
            return (Class<?>) rawType;
        } else if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            return Array.newInstance(getRawType(componentType), 0).getClass();
        } else if (type instanceof TypeVariable) {
            // we could use the variable's bounds, but that won't work if there are multiple.
            // having a raw type that's more general than necessary is okay
            return Object.class;
        } else if (type instanceof WildcardType) {
            return getRawType(((WildcardType) type).getUpperBounds()[0]);
        } else {
            String className = type == null ? "null" : type.getClass().getName();
            throw new IllegalArgumentException("Expected a Class, ParameterizedType, or "
                    + "GenericArrayType, but <" + type + "> is of type " + className);
        }
    }

    static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    /**
     * Returns true if {@code a} and {@code b} are equal.
     */
    public static boolean equals(Type a, Type b) {
        if (a == b) {
            // also handles (a == null && b == null)
            return true;
        } else if (a instanceof Class) {
            // Class already specifies equals().
            return a.equals(b);
        } else if (a instanceof ParameterizedType) {
            if (!(b instanceof ParameterizedType)) {
                return false;
            }

            // TODO: save a .clone() call
            ParameterizedType pa = (ParameterizedType) a;
            ParameterizedType pb = (ParameterizedType) b;
            return equal(pa.getOwnerType(), pb.getOwnerType())
                    && pa.getRawType().equals(pb.getRawType())
                    && Arrays.equals(pa.getActualTypeArguments(), pb.getActualTypeArguments());
        } else if (a instanceof GenericArrayType) {
            if (!(b instanceof GenericArrayType)) {
                return false;
            }

            GenericArrayType ga = (GenericArrayType) a;
            GenericArrayType gb = (GenericArrayType) b;
            return equals(ga.getGenericComponentType(), gb.getGenericComponentType());
        } else if (a instanceof WildcardType) {
            if (!(b instanceof WildcardType)) {
                return false;
            }

            WildcardType wa = (WildcardType) a;
            WildcardType wb = (WildcardType) b;
            return Arrays.equals(wa.getUpperBounds(), wb.getUpperBounds())
                    && Arrays.equals(wa.getLowerBounds(), wb.getLowerBounds());
        } else if (a instanceof TypeVariable) {
            if (!(b instanceof TypeVariable)) {
                return false;
            }
            TypeVariable<?> va = (TypeVariable<?>) a;
            TypeVariable<?> vb = (TypeVariable<?>) b;
            return va.getGenericDeclaration() == vb.getGenericDeclaration()
                    && va.getName().equals(vb.getName());
        } else {
            // This isn't a type we support. Could be a generic array type, wildcard type, etc.
            return false;
        }
    }

    static int hashCodeOrZero(Object o) {
        return o != null ? o.hashCode() : 0;
    }

    public static String typeToString(Type type) {
        return type instanceof Class ? ((Class<?>) type).getName() : type.toString();
    }

    /**
     * Returns the generic supertype for {@code supertype}. For example, given a class {@code
     * IntegerSet}, the result for when supertype is {@code Set.class} is {@code Set<Integer>} and the
     * result when the supertype is {@code Collection.class} is {@code Collection<Integer>}.
     */
    static Type getGenericSupertype(Type context, Class<?> rawType, Class<?> toResolve) {
        if (toResolve == rawType) {
            return context;
        }

        // we skip searching through interfaces if unknown is an interface
        if (toResolve.isInterface()) {
            Class<?>[] interfaces = rawType.getInterfaces();
            for (int i = 0, length = interfaces.length; i < length; i++) {
                if (interfaces[i] == toResolve) {
                    return rawType.getGenericInterfaces()[i];
                } else if (toResolve.isAssignableFrom(interfaces[i])) {
                    return getGenericSupertype(rawType.getGenericInterfaces()[i], interfaces[i], toResolve);
                }
            }
        }

        // check our supertypes
        if (!rawType.isInterface()) {
            while (rawType != Object.class) {
                Class<?> rawSupertype = rawType.getSuperclass();
                if (rawSupertype == toResolve) {
                    return rawType.getGenericSuperclass();
                } else if (toResolve.isAssignableFrom(rawSupertype)) {
                    return getGenericSupertype(rawType.getGenericSuperclass(), rawSupertype, toResolve);
                }
                rawType = rawSupertype;
            }
        }

        // we can't resolve this further
        return toResolve;
    }

    public static Type resolve(Type context, Class<?> contextRawType, Type toResolve) {
        return resolve(context, contextRawType, toResolve, new HashMap<TypeVariable<?>, Type>());
    }

    private static Type resolve(Type context, Class<?> contextRawType, Type toResolve,
                                Map<TypeVariable<?>, Type> visitedTypeVariables) {
        // this implementation is made a little more complicated in an attempt to avoid object-creation
        TypeVariable<?> resolving = null;
        while (true) {
            if (toResolve instanceof TypeVariable) {
                TypeVariable<?> typeVariable = (TypeVariable<?>) toResolve;
                Type previouslyResolved = visitedTypeVariables.get(typeVariable);
                if (previouslyResolved != null) {
                    // cannot reduce due to infinite recursion
                    return (previouslyResolved == Void.TYPE) ? toResolve : previouslyResolved;
                }

                // Insert a placeholder to mark the fact that we are in the process of resolving this type
                visitedTypeVariables.put(typeVariable, Void.TYPE);
                if (resolving == null) {
                    resolving = typeVariable;
                }

                toResolve = resolveTypeVariable(context, contextRawType, typeVariable);
                if (toResolve == typeVariable) {
                    break;
                }
            } else if (toResolve instanceof Class && ((Class<?>) toResolve).isArray()) {
                Class<?> original = (Class<?>) toResolve;
                Type componentType = original.getComponentType();
                Type newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables);
                toResolve = equal(componentType, newComponentType)
                        ? original
                        : arrayOf(newComponentType);
                break;
            } else if (toResolve instanceof GenericArrayType) {
                GenericArrayType original = (GenericArrayType) toResolve;
                Type componentType = original.getGenericComponentType();
                Type newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables);
                toResolve = equal(componentType, newComponentType)
                        ? original
                        : arrayOf(newComponentType);
                break;
            } else if (toResolve instanceof ParameterizedType) {
                ParameterizedType original = (ParameterizedType) toResolve;
                Type ownerType = original.getOwnerType();
                Type newOwnerType = resolve(context, contextRawType, ownerType, visitedTypeVariables);
                boolean changed = !equal(newOwnerType, ownerType);

                Type[] args = original.getActualTypeArguments();
                for (int t = 0, length = args.length; t < length; t++) {
                    Type resolvedTypeArgument = resolve(context, contextRawType, args[t], visitedTypeVariables);
                    if (!equal(resolvedTypeArgument, args[t])) {
                        if (!changed) {
                            args = args.clone();
                            changed = true;
                        }
                        args[t] = resolvedTypeArgument;
                    }
                }

                toResolve = changed
                        ? newParameterizedTypeWithOwner(newOwnerType, original.getRawType(), args)
                        : original;
                break;
            } else if (toResolve instanceof WildcardType) {
                WildcardType original = (WildcardType) toResolve;
                Type[] originalLowerBound = original.getLowerBounds();
                Type[] originalUpperBound = original.getUpperBounds();

                if (originalLowerBound.length == 1) {
                    Type lowerBound = resolve(context, contextRawType, originalLowerBound[0], visitedTypeVariables);
                    if (lowerBound != originalLowerBound[0]) {
                        toResolve = supertypeOf(lowerBound);
                        break;
                    }
                } else if (originalUpperBound.length == 1) {
                    Type upperBound = resolve(context, contextRawType, originalUpperBound[0], visitedTypeVariables);
                    if (upperBound != originalUpperBound[0]) {
                        toResolve = subtypeOf(upperBound);
                        break;
                    }
                }
                break;
            } else {
                break;
            }
        }
        // ensure that any in-process resolution gets updated with the final result
        if (resolving != null) {
            visitedTypeVariables.put(resolving, toResolve);
        }
        return toResolve;
    }

    static Type resolveTypeVariable(Type context, Class<?> contextRawType, TypeVariable<?> unknown) {
        Class<?> declaredByRaw = declaringClassOf(unknown);

        // we can't reduce this further
        if (declaredByRaw == null) {
            return unknown;
        }

        Type declaredBy = getGenericSupertype(context, contextRawType, declaredByRaw);
        if (declaredBy instanceof ParameterizedType) {
            int index = indexOf(declaredByRaw.getTypeParameters(), unknown);
            return ((ParameterizedType) declaredBy).getActualTypeArguments()[index];
        }

        return unknown;
    }

    private static int indexOf(Object[] array, Object toFind) {
        for (int i = 0, length = array.length; i < length; i++) {
            if (toFind.equals(array[i])) {
                return i;
            }
        }
        throw new NoSuchElementException();
    }

    /**
     * Returns the declaring class of {@code typeVariable}, or {@code null} if it was not declared by
     * a class.
     */
    private static Class<?> declaringClassOf(TypeVariable<?> typeVariable) {
        GenericDeclaration genericDeclaration = typeVariable.getGenericDeclaration();
        return genericDeclaration instanceof Class
                ? (Class<?>) genericDeclaration
                : null;
    }

    static void checkNotPrimitive(Type type) {
        checkArgument(!(type instanceof Class<?>) || !((Class<?>) type).isPrimitive());
    }

    private static final class ParameterizedTypeImpl
            implements ParameterizedType, Serializable {
        private final Type ownerType;
        private final Type rawType;
        private final Type[] typeArguments;

        public ParameterizedTypeImpl(Type ownerType, Type rawType, Type... typeArguments) {
            // require an owner type if the raw type needs it
            if (rawType instanceof Class<?>) {
                Class<?> rawTypeAsClass = (Class<?>) rawType;
                boolean isStaticOrTopLevelClass = Modifier.isStatic(rawTypeAsClass.getModifiers())
                        || rawTypeAsClass.getEnclosingClass() == null;
                checkArgument(ownerType != null || isStaticOrTopLevelClass);
            }

            this.ownerType = ownerType == null ? null : canonicalize(ownerType);
            this.rawType = canonicalize(rawType);
            this.typeArguments = typeArguments.clone();
            for (int t = 0, length = this.typeArguments.length; t < length; t++) {
                checkNotNull(this.typeArguments[t]);
                checkNotPrimitive(this.typeArguments[t]);
                this.typeArguments[t] = canonicalize(this.typeArguments[t]);
            }
        }

        @Override
        public Type[] getActualTypeArguments() {
            return typeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType
                    && BeanUtils.equals(this, (ParameterizedType) other);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(typeArguments)
                    ^ rawType.hashCode()
                    ^ hashCodeOrZero(ownerType);
        }

        @Override
        public String toString() {
            int length = typeArguments.length;
            if (length == 0) {
                return typeToString(rawType);
            }

            StringBuilder stringBuilder = new StringBuilder(30 * (length + 1));
            stringBuilder.append(typeToString(rawType)).append("<").append(typeToString(typeArguments[0]));
            for (int i = 1; i < length; i++) {
                stringBuilder.append(", ").append(typeToString(typeArguments[i]));
            }
            return stringBuilder.append(">").toString();
        }

        private static final long serialVersionUID = 0;
    }

    public static final class GenericArrayTypeImpl
            implements GenericArrayType, Serializable {
        private final Type componentType;

        public GenericArrayTypeImpl(Type componentType) {
            this.componentType = canonicalize(componentType);
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GenericArrayType
                    && BeanUtils.equals(this, (GenericArrayType) o);
        }

        @Override
        public int hashCode() {
            return componentType.hashCode();
        }

        @Override
        public String toString() {
            return typeToString(componentType) + "[]";
        }

        private static final long serialVersionUID = 0;
    }

    /**
     * The WildcardType interface supports multiple upper bounds and multiple
     * lower bounds. We only support what the Java 6 language needs - at most one
     * bound. If a lower bound is set, the upper bound must be Object.class.
     */
    private static final class WildcardTypeImpl
            implements WildcardType, Serializable {
        private final Type upperBound;
        private final Type lowerBound;

        public WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
            checkArgument(lowerBounds.length <= 1);
            checkArgument(upperBounds.length == 1);

            if (lowerBounds.length == 1) {
                checkNotNull(lowerBounds[0]);
                checkNotPrimitive(lowerBounds[0]);
                checkArgument(upperBounds[0] == Object.class);
                this.lowerBound = canonicalize(lowerBounds[0]);
                this.upperBound = Object.class;
            } else {
                checkNotNull(upperBounds[0]);
                checkNotPrimitive(upperBounds[0]);
                this.lowerBound = null;
                this.upperBound = canonicalize(upperBounds[0]);
            }
        }

        @Override
        public Type[] getUpperBounds() {
            return new Type[]{upperBound};
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBound != null ? new Type[]{lowerBound} : EMPTY_TYPE_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WildcardType
                    && BeanUtils.equals(this, (WildcardType) other);
        }

        @Override
        public int hashCode() {
            // this equals Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds());
            return (lowerBound != null ? 31 + lowerBound.hashCode() : 1)
                    ^ (31 + upperBound.hashCode());
        }

        @Override
        public String toString() {
            if (lowerBound != null) {
                return "? super " + typeToString(lowerBound);
            } else if (upperBound == Object.class) {
                return "?";
            } else {
                return "? extends " + typeToString(upperBound);
            }
        }

        private static final long serialVersionUID = 0;
    }

    static void checkArgument(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException();
        }
    }

    public static <T> T checkNotNull(T obj) {
        if (obj == null) {
            throw new NullPointerException();
        }
        return obj;
    }
}
