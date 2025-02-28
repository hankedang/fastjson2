package com.alibaba.fastjson2.reader;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.util.ReferenceKey;
import com.alibaba.fastjson2.util.TypeUtils;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.alibaba.fastjson2.JSONB.Constants.*;

class ObjectReaderImplMapTyped
        implements ObjectReader {
    final Class mapType;
    final Class instanceType;
    final Type keyType;
    final Type valueType;
    final Class valueClass;
    final long features;
    final Function builder;

    ObjectReader valueObjectReader;
    ObjectReader keyObjectReader;

    public ObjectReaderImplMapTyped(Class mapType, Class instanceType, Type keyType, Type valueType, long features, Function builder) {
        if (keyType == Object.class) {
            keyType = null;
        }

        this.mapType = mapType;
        this.instanceType = instanceType;
        this.keyType = keyType;
        this.valueType = valueType;
        this.valueClass = TypeUtils.getClass(valueType);
        this.features = features;
        this.builder = builder;
    }

    @Override
    public Class getObjectClass() {
        return mapType;
    }

    @Override
    public Object createInstance(Map input, long features) {
        ObjectReaderProvider provider = JSONFactory.getDefaultObjectReaderProvider();

        Map<String, Object> object = (Map<String, Object>) createInstance();
        for (Iterator<Map.Entry> it = input.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry entry = it.next();
            String fieldName = entry.getKey().toString();
            Object fieldValue = entry.getValue();

            Object value = fieldValue;
            Class<?> valueClass = value.getClass();
            Function typeConvert = provider.getTypeConvert(valueClass, valueType);
            if (typeConvert != null) {
                value = typeConvert.apply(value);
            } else if (value instanceof Map) {
                Map map = (Map) value;
                if (valueObjectReader == null) {
                    valueObjectReader = provider.getObjectReader(valueType);
                }
                value = valueObjectReader.createInstance(map, features);
            } else if (value instanceof Collection) {
                if (valueObjectReader == null) {
                    valueObjectReader = provider.getObjectReader(valueType);
                }
                value = valueObjectReader.createInstance((Collection) value);
            } else {
                if (!valueClass.isInstance(value)) {
                    throw new JSONException("can not convert from " + valueClass + " to " + valueType);
                }
            }
            object.put(fieldName, value);
        }

        if (builder != null) {
            return builder.apply(object);
        }

        return object;
    }

    @Override
    public Object createInstance(long features) {
        if (instanceType != null && !instanceType.isInterface()) {
            try {
                return instanceType.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new JSONException("create map error");
            }
        }
        return new HashMap();
    }

    @Override
    public Object readJSONBObject(JSONReader jsonReader, long features) {
        ObjectReader objectReader = null;
        Function builder = this.builder;
        if (jsonReader.getType() == BC_TYPED_ANY) {
            objectReader = jsonReader.checkAutoType(mapType, 0, this.features | features);

            if (objectReader != null && objectReader != this) {
                builder = objectReader.getBuildFunction();
                if (!(objectReader instanceof ObjectReaderImplMap) && !(objectReader instanceof ObjectReaderImplMapTyped)) {
                    return objectReader.readJSONBObject(jsonReader, features);
                }
            }
        }

        byte firstType = jsonReader.getType();
        if (firstType == BC_NULL) {
            jsonReader.next();
            return null;
        }

        if (firstType == BC_OBJECT) {
            jsonReader.next();
        }

        JSONReader.Context context = jsonReader.getContext();
        long contextFeatures = features | context.getFeatures();

        Map object;
        if (objectReader != null) {
            object = (Map) objectReader.createInstance(contextFeatures);
        } else {
            object = instanceType == HashMap.class
                    ? new HashMap<>()
                    : (Map) createInstance();
        }

        for (int i = 0; ; ++i) {
            byte type = jsonReader.getType();
            if (type == BC_OBJECT_END) {
                jsonReader.next();
                break;
            }

            Object name;
            if (keyType == String.class || jsonReader.isString()) {
                name = jsonReader.readFieldName();
            } else {
                if (jsonReader.isReference()) {
                    String reference = jsonReader.readReference();
                    name = new ReferenceKey(i);
                    jsonReader.addResolveTask(object, name, JSONPath.of(reference));
                } else {
                    if (keyObjectReader == null && keyType != null) {
                        keyObjectReader = jsonReader.getObjectReader(keyType);
                    }

                    if (keyObjectReader == null) {
                        name = jsonReader.readAny();
                    } else {
                        name = keyObjectReader.readJSONBObject(jsonReader, features);
                    }
                }
            }

            if (jsonReader.isReference()) {
                String reference = jsonReader.readReference();
                if ("..".equals(reference)) {
                    object.put(name, object);
                } else {
                    jsonReader.addResolveTask((Map) object, name, JSONPath.of(reference));
                    if (!(object instanceof ConcurrentMap)) {
                        object.put(name, null);
                    }
                }
                continue;
            }

            if (jsonReader.nextIfNull()) {
                object.put(name, null);
                continue;
            }

            Object value;
            if (valueType == Object.class) {
                value = jsonReader.readAny();
            } else {
                ObjectReader autoTypeValueReader = jsonReader.checkAutoType(valueClass, 0, features);
                if (autoTypeValueReader != null) {
                    value = autoTypeValueReader.readJSONBObject(jsonReader, features);
                } else {
                    if (valueObjectReader == null) {
                        valueObjectReader = jsonReader.getObjectReader(valueType);
                    }
                    value = valueObjectReader.readJSONBObject(jsonReader, features);
                }
            }
            object.put(name, value);
        }

        if (builder != null) {
            return builder.apply(object);
        }

        return object;
    }

    @Override
    public Object readObject(JSONReader jsonReader, long features) {
        boolean match = jsonReader.nextIfMatch('{');
        if (!match) {
            throw new JSONException(jsonReader.info("expect '{', but '['"));
        }

        JSONReader.Context context = jsonReader.getContext();
        long contextFeatures = context.getFeatures() | features;
        Map object;
        if (instanceType == HashMap.class) {
            Supplier<Map> objectSupplier = context.getObjectSupplier();
            if (mapType == Map.class && objectSupplier != null) {
                object = objectSupplier.get();
            } else {
                object = new HashMap<>();
            }
        } else {
            object = (Map) createInstance(contextFeatures);
        }

        for (; ; ) {
            if (jsonReader.nextIfMatch('}')) {
                break;
            }

            Object name;
            if (keyType == String.class) {
                name = jsonReader.readFieldName();
            } else {
                name = jsonReader.read(keyType);
                jsonReader.nextIfMatch(':');
            }
            if (valueObjectReader == null) {
                valueObjectReader = jsonReader.getObjectReader(valueType);
            }
            Object value = valueObjectReader.readObject(jsonReader, 0);
            Object origin = object.put(name, value);
            if (origin != null) {
                if ((contextFeatures & JSONReader.Feature.DuplicateKeyValueAsArray.mask) != 0) {
                    if (origin instanceof Collection) {
                        ((Collection) origin).add(value);
                        object.put(name, value);
                    } else {
                        JSONArray array = JSONArray.of(origin, value);
                        object.put(name, array);
                    }
                }
            }
        }

        jsonReader.nextIfMatch(',');

        if (builder != null) {
            return builder.apply(object);
        }

        return object;
    }
}
