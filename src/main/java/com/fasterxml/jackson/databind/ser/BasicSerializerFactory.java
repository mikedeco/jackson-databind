package com.fasterxml.jackson.databind.ser;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.NoClass;
import com.fasterxml.jackson.databind.cfg.SerializerFactoryConfig;
import com.fasterxml.jackson.databind.ext.OptionalHandlerFactory;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.impl.IndexedStringListSerializer;
import com.fasterxml.jackson.databind.ser.impl.StringArraySerializer;
import com.fasterxml.jackson.databind.ser.impl.StringCollectionSerializer;
import com.fasterxml.jackson.databind.ser.std.*;
import com.fasterxml.jackson.databind.type.*;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.databind.util.Converter;
import com.fasterxml.jackson.databind.util.EnumValues;
import com.fasterxml.jackson.databind.util.TokenBuffer;

/**
 * Factory class that can provide serializers for standard JDK classes,
 * as well as custom classes that extend standard classes or implement
 * one of "well-known" interfaces (such as {@link java.util.Collection}).
 *<p>
 * Since all the serializers are eagerly instantiated, and there is
 * no additional introspection or customizability of these types,
 * this factory is essentially stateless.
 */
public abstract class BasicSerializerFactory
    extends SerializerFactory
    implements java.io.Serializable
{
    private static final long serialVersionUID = -1416617628045738132L;

    /*
    /**********************************************************
    /* Configuration, lookup tables/maps
    /**********************************************************
     */

    /**
     * Since these are all JDK classes, we shouldn't have to worry
     * about ClassLoader used to load them. Rather, we can just
     * use the class name, and keep things simple and efficient.
     */
    protected final static HashMap<String, JsonSerializer<?>> _concrete =
        new HashMap<String, JsonSerializer<?>>();
    
    /**
     * Actually it may not make much sense to eagerly instantiate all
     * kinds of serializers: so this Map actually contains class references,
     * not instances
     */
    protected final static HashMap<String, Class<? extends JsonSerializer<?>>> _concreteLazy =
        new HashMap<String, Class<? extends JsonSerializer<?>>>();
    
    static {
        /* String and string-like types (note: date types explicitly
         * not included -- can use either textual or numeric serialization)
         */
        _concrete.put(String.class.getName(), new StringSerializer());
        final ToStringSerializer sls = ToStringSerializer.instance;
        _concrete.put(StringBuffer.class.getName(), sls);
        _concrete.put(StringBuilder.class.getName(), sls);
        _concrete.put(Character.class.getName(), sls);
        _concrete.put(Character.TYPE.getName(), sls);

        // Primitives/wrappers for primitives (primitives needed for Beans)
        NumberSerializers.addAll(_concrete);
        _concrete.put(Boolean.TYPE.getName(), new BooleanSerializer(true));
        _concrete.put(Boolean.class.getName(), new BooleanSerializer(false));

        // Other numbers, more complicated
        final JsonSerializer<?> ns = new NumberSerializers.NumberSerializer();
        _concrete.put(BigInteger.class.getName(), ns);
        _concrete.put(BigDecimal.class.getName(), ns);
        
        // Other discrete non-container types:
        // First, Date/Time zoo:
        _concrete.put(Calendar.class.getName(), CalendarSerializer.instance);
        DateSerializer dateSer = DateSerializer.instance;
        _concrete.put(java.util.Date.class.getName(), dateSer);
        // note: timestamps are very similar to java.util.Date, thus serialized as such
        _concrete.put(java.sql.Timestamp.class.getName(), dateSer);
        _concrete.put(java.sql.Date.class.getName(), new SqlDateSerializer());
        _concrete.put(java.sql.Time.class.getName(), new SqlTimeSerializer());

        // And then other standard non-structured JDK types
        for (Map.Entry<Class<?>,Object> en : new StdJdkSerializers().provide()) {
            Object value = en.getValue();
            if (value instanceof JsonSerializer<?>) {
                _concrete.put(en.getKey().getName(), (JsonSerializer<?>) value);
            } else if (value instanceof Class<?>) {
                @SuppressWarnings("unchecked")
                Class<? extends JsonSerializer<?>> cls = (Class<? extends JsonSerializer<?>>) value;
                _concreteLazy.put(en.getKey().getName(), cls);
            } else { // should never happen, but:
                throw new IllegalStateException("Internal error: unrecognized value of type "+en.getClass().getName());
            }
        }

        // Jackson-specific type(s)
        // (Q: can this ever be sub-classed?)
        _concreteLazy.put(TokenBuffer.class.getName(), TokenBufferSerializer.class);
    }

    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */
    
    /**
     * Configuration settings for this factory; immutable instance (just like this
     * factory), new version created via copy-constructor (fluent-style)
     */
    protected final SerializerFactoryConfig _factoryConfig;
    
    /**
     * Helper object used to deal with serializers for optional JDK types (like ones
     * omitted from GAE, Android)
     */
    protected OptionalHandlerFactory optionalHandlers = OptionalHandlerFactory.instance;

    /*
    /**********************************************************
    /* Life cycle
    /**********************************************************
     */

    /**
     * We will provide default constructor to allow sub-classing,
     * but make it protected so that no non-singleton instances of
     * the class will be instantiated.
     */
    protected BasicSerializerFactory(SerializerFactoryConfig config) {
        _factoryConfig = (config == null) ? new SerializerFactoryConfig() : config;
    }
    
    /**
     * Method for getting current {@link SerializerFactoryConfig}.
      *<p>
     * Note that since instances are immutable, you can NOT change settings
     * by accessing an instance and calling methods: this will simply create
     * new instance of config object.
     */
    public SerializerFactoryConfig getFactoryConfig() {
        return _factoryConfig;
    }

    /**
     * Method used for creating a new instance of this factory, but with different
     * configuration. Reason for specifying factory method (instead of plain constructor)
     * is to allow proper sub-classing of factories.
     *<p>
     * Note that custom sub-classes generally <b>must override</b> implementation
     * of this method, as it usually requires instantiating a new instance of
     * factory type. Check out javadocs for
     * {@link com.fasterxml.jackson.databind.ser.BeanSerializerFactory} for more details.
     */
    public abstract SerializerFactory withConfig(SerializerFactoryConfig config);

    /**
     * Convenience method for creating a new factory instance with an additional
     * serializer provider.
     */
    @Override
    public final SerializerFactory withAdditionalSerializers(Serializers additional) {
        return withConfig(_factoryConfig.withAdditionalSerializers(additional));
    }

    /**
     * Convenience method for creating a new factory instance with an additional
     * key serializer provider.
     */
    @Override
    public final SerializerFactory withAdditionalKeySerializers(Serializers additional) {
        return withConfig(_factoryConfig.withAdditionalKeySerializers(additional));
    }
    
    /**
     * Convenience method for creating a new factory instance with additional bean
     * serializer modifier.
     */
    @Override
    public final SerializerFactory withSerializerModifier(BeanSerializerModifier modifier) {
        return withConfig(_factoryConfig.withSerializerModifier(modifier));
    }

    /*
    /**********************************************************
    /* SerializerFactory impl
    /**********************************************************
     */
    
    // Implemented by sub-classes
    @Override
    public abstract JsonSerializer<Object> createSerializer(SerializerProvider prov,
            JavaType type)
        throws JsonMappingException;

    @Override
    @SuppressWarnings("unchecked")
    public JsonSerializer<Object> createKeySerializer(SerializationConfig config,
            JavaType keyType, JsonSerializer<Object> defaultImpl)
    {
        // We should not need any member method info; at most class annotations for Map type
        BeanDescription beanDesc = config.introspectClassAnnotations(keyType.getRawClass());
        JsonSerializer<?> ser = null;
        // Minor optimization: to avoid constructing beanDesc, bail out if none registered
        if (_factoryConfig.hasKeySerializers()) {
            // Only thing we have here are module-provided key serializers:
            for (Serializers serializers : _factoryConfig.keySerializers()) {
                ser = serializers.findSerializer(config, keyType, beanDesc);
                if (ser != null) {
                    break;
                }
            }
        }
        if (ser == null) {
            ser = defaultImpl;
            if (ser == null) {
                ser = StdKeySerializers.getStdKeySerializer(keyType);
            }
        }
        
        // [Issue#120]: Allow post-processing
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                ser = mod.modifyKeySerializer(config, keyType, beanDesc, ser);
            }
        }
        return (JsonSerializer<Object>) ser;
    }
    
    /**
     * Method called to construct a type serializer for values with given declared
     * base type. This is called for values other than those of bean property
     * types.
     */
    @Override
    public TypeSerializer createTypeSerializer(SerializationConfig config,
            JavaType baseType)
    {
        BeanDescription bean = config.introspectClassAnnotations(baseType.getRawClass());
        AnnotatedClass ac = bean.getClassInfo();
        AnnotationIntrospector ai = config.getAnnotationIntrospector();
        TypeResolverBuilder<?> b = ai.findTypeResolver(config, ac, baseType);
        /* Ok: if there is no explicit type info handler, we may want to
         * use a default. If so, config object knows what to use.
         */
        Collection<NamedType> subtypes = null;
        if (b == null) {
            b = config.getDefaultTyper(baseType);
        } else {
            subtypes = config.getSubtypeResolver().collectAndResolveSubtypes(ac, config, ai);
        }
        if (b == null) {
            return null;
        }
        return b.buildTypeSerializer(config, baseType, subtypes);
    }

    /*
    /**********************************************************
    /* Additional API for other core classes
    /**********************************************************
     */

    public final JsonSerializer<?> getNullSerializer() {
        return NullSerializer.instance;
    }    

    protected abstract Iterable<Serializers> customSerializers();
    
    /*
    /**********************************************************
    /* Overridable secondary serializer accessor methods
    /**********************************************************
     */
    
    /**
     * Method that will use fast lookup (and identity comparison) methods to
     * see if we know serializer to use for given type.
     */
    protected final JsonSerializer<?> findSerializerByLookup(JavaType type,
            SerializationConfig config, BeanDescription beanDesc,
            boolean staticTyping)
    {
        Class<?> raw = type.getRawClass();
        String clsName = raw.getName();
        JsonSerializer<?> ser = _concrete.get(clsName);
        if (ser != null) {
            return ser;
        }
        Class<? extends JsonSerializer<?>> serClass = _concreteLazy.get(clsName);
        if (serClass != null) {
            try {
                return serClass.newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate standard serializer (of type "+serClass.getName()+"): "
                        +e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Method called to see if one of primary per-class annotations
     * (or related, like implementing of {@link JsonSerializable})
     * determines the serializer to use.
     *<p>
     * Currently handles things like:
     *<ul>
     * <li>If type implements {@link JsonSerializable}, use that
     *  </li>
     * <li>If type has {@link com.fasterxml.jackson.annotation.JsonValue} annotation (or equivalent), build serializer
     *    based on that property
     *  </li>
     *</ul>
     *
     * @since 2.0
     */
    protected final JsonSerializer<?> findSerializerByAnnotations(SerializerProvider prov, 
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        Class<?> raw = type.getRawClass();
        // First: JsonSerializable?
        if (JsonSerializable.class.isAssignableFrom(raw)) {
            return SerializableSerializer.instance;
        }
        // Second: @JsonValue for any type
        AnnotatedMethod valueMethod = beanDesc.findJsonValueMethod();
        if (valueMethod != null) {
            Method m = valueMethod.getAnnotated();
            if (prov.canOverrideAccessModifiers()) {
                ClassUtil.checkAndFixAccess(m);
            }
            JsonSerializer<Object> ser = findSerializerFromAnnotation(prov, valueMethod);
            return new JsonValueSerializer(m, ser);
        }
        // No well-known annotations...
        return null;
    }
    
    /**
     * Method for checking if we can determine serializer to use based on set of
     * known primary types, checking for set of known base types (exact matches
     * having been compared against with <code>findSerializerByLookup</code>).
     * This does not include "secondary" interfaces, but
     * mostly concrete or abstract base classes.
     */
    protected final JsonSerializer<?> findSerializerByPrimaryType(SerializerProvider prov, 
            JavaType type, BeanDescription beanDesc,
            boolean staticTyping)
        throws JsonMappingException
    {
        Class<?> raw = type.getRawClass();
        // One unfortunate special case, as per [JACKSON-484]
        if (InetAddress.class.isAssignableFrom(raw)) {
            return InetAddressSerializer.instance;
        }
        // ... and another one, [JACKSON-522], for TimeZone
        if (TimeZone.class.isAssignableFrom(raw)) {
            return TimeZoneSerializer.instance;
        }
        // and yet one more [JACKSON-789]
        if (java.nio.charset.Charset.class.isAssignableFrom(raw)) {
            return ToStringSerializer.instance;
        }
        
        // Then check for optional/external serializers [JACKSON-386]
        JsonSerializer<?> ser = optionalHandlers.findSerializer(prov.getConfig(), type);
        if (ser != null) {
            return ser;
        }
        
        if (Number.class.isAssignableFrom(raw)) {
            return NumberSerializers.NumberSerializer.instance;
        }
        if (Enum.class.isAssignableFrom(raw)) {
            return buildEnumSerializer(prov.getConfig(), type, beanDesc);
        }
        if (Calendar.class.isAssignableFrom(raw)) {
            return CalendarSerializer.instance;
        }
        if (java.util.Date.class.isAssignableFrom(raw)) {
            return DateSerializer.instance;
        }
        return null;
    }
        
    /**
     * Reflection-based serialized find method, which checks if
     * given class implements one of recognized "add-on" interfaces.
     * Add-on here means a role that is usually or can be a secondary
     * trait: for example,
     * bean classes may implement {@link Iterable}, but their main
     * function is usually something else. The reason for
     */
    protected final JsonSerializer<?> findSerializerByAddonType(SerializationConfig config,
            JavaType javaType, BeanDescription beanDesc,
            boolean staticTyping)
        throws JsonMappingException
    {
        Class<?> type = javaType.getRawClass();

        // These need to be in decreasing order of specificity...
        if (Iterator.class.isAssignableFrom(type)) {
            return buildIteratorSerializer(config, javaType, beanDesc, staticTyping);
        }
        if (Iterable.class.isAssignableFrom(type)) {
            return buildIterableSerializer(config, javaType, beanDesc,  staticTyping);
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return ToStringSerializer.instance;
        }
        return null;
    }
    
    /**
     * Helper method called to check if a class or method
     * has an annotation
     * (@link com.fasterxml.jackson.databind.annotation.JsonSerialize#using)
     * that tells the class to use for serialization.
     * Returns null if no such annotation found.
     */
    @SuppressWarnings("unchecked")
    protected JsonSerializer<Object> findSerializerFromAnnotation(SerializerProvider prov,
            Annotated a)
        throws JsonMappingException
    {
        Object serDef = prov.getAnnotationIntrospector().findSerializer(a);
        if (serDef == null) {
            return null;
        }
        JsonSerializer<Object> ser = prov.serializerInstance(a, serDef);
        // One more thing however: may need to also apply a converter:
        return (JsonSerializer<Object>) findConvertingSerializer(prov, a, ser);
    }

    /**
     * Helper method that will check whether given annotated entity (usually class,
     * but may also be a property accessor) indicates that a {@link Converter} is to
     * be used; and if so, to construct and return suitable serializer for it.
     * If not, will simply return given serializer as is.
     */
    protected JsonSerializer<?> findConvertingSerializer(SerializerProvider prov,
            Annotated a, JsonSerializer<?> ser)
        throws JsonMappingException
    {
        Converter<Object,Object> conv = findConverter(prov, a);
        if (conv == null) {
            return ser;
        }
        JavaType delegateType = conv.getOutputType(prov.getTypeFactory());
        return new StdDelegatingSerializer(conv, delegateType, ser);
    }

    protected Converter<Object,Object> findConverter(SerializerProvider prov,
            Annotated a)
        throws JsonMappingException
    {
        Object convDef = prov.getAnnotationIntrospector().findSerializationConverter(a);
        if (convDef == null) {
            return null;
        }
        return prov.converterInstance(a, convDef);
    }
    
    /*
    /**********************************************************
    /* Factory methods, container types:
    /**********************************************************
     */

    /**
     * Deprecated method; final to help identify problems with sub-classes,
     * as this method will NOT be called any more in 2.1
     * 
     * @deprecated Since 2.1 (removed 'property' argument)
     */
    @Deprecated
    protected final JsonSerializer<?> buildContainerSerializer(SerializerProvider prov,
            JavaType type, BeanDescription beanDesc, BeanProperty property, boolean staticTyping)
        throws JsonMappingException
    {
        return  buildContainerSerializer(prov, type, beanDesc, staticTyping);
    }
    
    /**
     * @since 2.1
     */
    protected JsonSerializer<?> buildContainerSerializer(SerializerProvider prov,
            JavaType type, BeanDescription beanDesc, boolean staticTyping)
        throws JsonMappingException
    {
        final SerializationConfig config = prov.getConfig();
        // Let's see what we can learn about element/content/value type, type serializer for it:
        JavaType elementType = type.getContentType();
        TypeSerializer elementTypeSerializer = createTypeSerializer(config,
                elementType);

        // if elements have type serializer, can not force static typing:
        if (elementTypeSerializer != null) {
            staticTyping = false;
        }
        JsonSerializer<Object> elementValueSerializer = _findContentSerializer(prov,
                beanDesc.getClassInfo());
        
        if (type.isMapLikeType()) { // implements java.util.Map
            MapLikeType mlt = (MapLikeType) type;
            /* 29-Sep-2012, tatu: This is actually too early to (try to) find
             *  key serializer from property annotations, and can lead to caching
             *  issues (see [Issue#75]). Instead, must be done from 'createContextual()' call.
             *  But we do need to check class annotations.
             */
            JsonSerializer<Object> keySerializer = _findKeySerializer(prov, beanDesc.getClassInfo());
            if (mlt.isTrueMapType()) {
                return buildMapSerializer(config, (MapType) mlt, beanDesc, staticTyping,
                        keySerializer, elementTypeSerializer, elementValueSerializer);
            }
            // Only custom serializers may be available:
            for (Serializers serializers : customSerializers()) {
                MapLikeType mlType = (MapLikeType) type;
                JsonSerializer<?> ser = serializers.findMapLikeSerializer(config,
                        mlType, beanDesc, keySerializer, elementTypeSerializer, elementValueSerializer);
                if (ser != null) {
                    // [Issue#120]: Allow post-processing
                    if (_factoryConfig.hasSerializerModifiers()) {
                        for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                            ser = mod.modifyMapLikeSerializer(config, mlType, beanDesc, ser);
                        }
                    }
                    return ser;
                }
            }
            return null;
        }
        if (type.isCollectionLikeType()) {
            CollectionLikeType clt = (CollectionLikeType) type;
            if (clt.isTrueCollectionType()) {
                return buildCollectionSerializer(config,  (CollectionType) clt, beanDesc, staticTyping,
                        elementTypeSerializer, elementValueSerializer);
            }
            CollectionLikeType clType = (CollectionLikeType) type;
            // Only custom variants for this:
            for (Serializers serializers : customSerializers()) {
                JsonSerializer<?> ser = serializers.findCollectionLikeSerializer(config,
                        clType, beanDesc, elementTypeSerializer, elementValueSerializer);
                if (ser != null) {
                    // [Issue#120]: Allow post-processing
                    if (_factoryConfig.hasSerializerModifiers()) {
                        for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                            ser = mod.modifyCollectionLikeSerializer(config, clType, beanDesc, ser);
                        }
                    }
                    return ser;
                }
            }
            // fall through either way (whether shape dictates serialization as POJO or not)
            return null;
        }
        if (type.isArrayType()) {
            return buildArraySerializer(config, (ArrayType) type, beanDesc, staticTyping,
                    elementTypeSerializer, elementValueSerializer);
        }
        return null;
    }

    /**
     * Deprecated method; final to help identify problems with sub-classes,
     * as this method will NOT be called any more in 2.1
     * 
     * @deprecated Since 2.1
     */
    @Deprecated
    protected final JsonSerializer<?> buildCollectionSerializer(SerializationConfig config,
            CollectionType type,
            BeanDescription beanDesc, BeanProperty property,
            boolean staticTyping,
            TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) 
        throws JsonMappingException
    {
        return buildCollectionSerializer(config, type, beanDesc,
                staticTyping, elementTypeSerializer, elementValueSerializer);
    }
    
    /**
     * Helper method that handles configuration details when constructing serializers for
     * {@link java.util.List} types that support efficient by-index access
     * 
     * @since 2.1
     */
    protected JsonSerializer<?> buildCollectionSerializer(SerializationConfig config,
            CollectionType type, BeanDescription beanDesc, boolean staticTyping,
            TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) 
        throws JsonMappingException
    {
        JsonSerializer<?> ser = null;
        // Module-provided custom collection serializers?
        for (Serializers serializers : customSerializers()) {
            ser = serializers.findCollectionSerializer(config,
                    type, beanDesc, elementTypeSerializer, elementValueSerializer);
            if (ser != null) {
                break;
            }
        }

        // As per [Issue#24], may want to use alternate shape, serialize as JSON Object.
        // Challenge here is that EnumSerializer does not know how to produce
        // POJO style serialization, so we must handle that special case separately;
        // otherwise pass it to EnumSerializer.
        if (ser == null) {
            JsonFormat.Value format = beanDesc.findExpectedFormat(null);
            if (format != null && format.getShape() == JsonFormat.Shape.OBJECT) {
                return null;
            }
            Class<?> raw = type.getRawClass();
            if (EnumSet.class.isAssignableFrom(raw)) {
                // this may or may not be available (Class doesn't; type of field/method does)
                JavaType enumType = type.getContentType();
                // and even if nominally there is something, only use if it really is enum
                if (!enumType.isEnumType()) {
                    enumType = null;
                }
                ser = StdContainerSerializers.enumSetSerializer(enumType);
            } else {
                Class<?> elementRaw = type.getContentType().getRawClass();
                if (isIndexedList(raw)) {
                    if (elementRaw == String.class) {
                        // [JACKSON-829] Must NOT use if we have custom serializer
                        if (elementValueSerializer == null || ClassUtil.isJacksonStdImpl(elementValueSerializer)) {
                            ser = IndexedStringListSerializer.instance;
                        }
                    } else {
                        ser = StdContainerSerializers.indexedListSerializer(type.getContentType(), staticTyping,
                            elementTypeSerializer, elementValueSerializer);
                    }
                } else if (elementRaw == String.class) {
                    // [JACKSON-829] Must NOT use if we have custom serializer
                    if (elementValueSerializer == null || ClassUtil.isJacksonStdImpl(elementValueSerializer)) {
                        ser = StringCollectionSerializer.instance;
                    }
                }
                if (ser == null) {
                    ser = StdContainerSerializers.collectionSerializer(type.getContentType(), staticTyping,
                            elementTypeSerializer, elementValueSerializer);
                }
            }
        }
        // [Issue#120]: Allow post-processing
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                ser = mod.modifyCollectionSerializer(config, type, beanDesc, ser);
            }
        }
        return ser;
    }
    
    protected boolean isIndexedList(Class<?> cls)
    {
        return RandomAccess.class.isAssignableFrom(cls);
    }
    
    /*
    /**********************************************************
    /* Factory methods, for Maps
    /**********************************************************
     */
    
    /**
     * Helper method that handles configuration details when constructing serializers for
     * {@link java.util.Map} types.
     */
    protected JsonSerializer<?> buildMapSerializer(SerializationConfig config,
            MapType type, BeanDescription beanDesc,
            boolean staticTyping, JsonSerializer<Object> keySerializer,
            TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer)
        throws JsonMappingException
    {
        JsonSerializer<?> ser = null;
        for (Serializers serializers : customSerializers()) {
            ser = serializers.findMapSerializer(config, type, beanDesc,
                    keySerializer, elementTypeSerializer, elementValueSerializer);
            if (ser != null) {
                break;
            }
        }
        if (ser == null) {
            if (EnumMap.class.isAssignableFrom(type.getRawClass())) {
                JavaType keyType = type.getKeyType();
                // Need to find key enum values...
                EnumValues enums = null;
                if (keyType.isEnumType()) { // non-enum if we got it as type erased class (from instance)
                    @SuppressWarnings("unchecked")
                    Class<Enum<?>> enumClass = (Class<Enum<?>>) keyType.getRawClass();
                    enums = EnumValues.construct(enumClass, config.getAnnotationIntrospector());
                }
                ser = new EnumMapSerializer(type.getContentType(), staticTyping, enums,
                    elementTypeSerializer, elementValueSerializer);
            } else {
                ser = MapSerializer.construct(config.getAnnotationIntrospector().findPropertiesToIgnore(beanDesc.getClassInfo()),
                    type, staticTyping, elementTypeSerializer,
                    keySerializer, elementValueSerializer);
            }
        }
        // [Issue#120]: Allow post-processing
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                ser = mod.modifyMapSerializer(config, type, beanDesc, ser);
            }
        }
        return ser;
    }

    /*
    /**********************************************************
    /* Factory methods, for Arrays
    /**********************************************************
     */
    
    /**
     * Helper method that handles configuration details when constructing serializers for
     * <code>Object[]</code> (and subtypes, except for String).
     */
    protected JsonSerializer<?> buildArraySerializer(SerializationConfig config,
            ArrayType type, BeanDescription beanDesc,
            boolean staticTyping,
            TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer)
        throws JsonMappingException
    {
        JsonSerializer<?> ser = null;        
         // Module-provided custom collection serializers?
         for (Serializers serializers : customSerializers()) {
             ser = serializers.findArraySerializer(config,
                     type, beanDesc, elementTypeSerializer, elementValueSerializer);
             if (ser != null) {
                 break;
             }
         }
         if (ser == null) {
             Class<?> raw = type.getRawClass();
             // Important: do NOT use standard serializers if non-standard element value serializer specified
             if (elementValueSerializer == null || ClassUtil.isJacksonStdImpl(elementValueSerializer)) {
                 if (String[].class == raw) {
                     ser = StringArraySerializer.instance;
                 } else {
                     // other standard types?
                     ser = StdArraySerializers.findStandardImpl(raw);
                 }
             }
             if (ser == null) {
                 ser = new ObjectArraySerializer(type.getContentType(), staticTyping, elementTypeSerializer,
                         elementValueSerializer);
             }
         }
         // [Issue#120]: Allow post-processing
         if (_factoryConfig.hasSerializerModifiers()) {
             for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                 ser = mod.modifyArraySerializer(config, type, beanDesc, ser);
             }
         }
         return ser;
    }

    /*
    /**********************************************************
    /* Factory methods, for non-container types
    /**********************************************************
     */

    protected JsonSerializer<?> buildIteratorSerializer(SerializationConfig config,
            JavaType type, BeanDescription beanDesc,
            boolean staticTyping)
        throws JsonMappingException
    {
        // if there's generic type, it'll be the first contained type
        JavaType valueType = type.containedType(0);
        if (valueType == null) {
            valueType = TypeFactory.unknownType();
        }
        TypeSerializer vts = createTypeSerializer(config, valueType);
        return StdContainerSerializers.iteratorSerializer(valueType,
                usesStaticTyping(config, beanDesc, vts), vts);
    }
    
    protected JsonSerializer<?> buildIterableSerializer(SerializationConfig config,
            JavaType type, BeanDescription beanDesc,
            boolean staticTyping)
        throws JsonMappingException
    {
        // if there's generic type, it'll be the first contained type
        JavaType valueType = type.containedType(0);
        if (valueType == null) {
            valueType = TypeFactory.unknownType();
        }
        TypeSerializer vts = createTypeSerializer(config, valueType);
        return StdContainerSerializers.iterableSerializer(valueType,
                usesStaticTyping(config, beanDesc, vts), vts);
    }
    
    protected JsonSerializer<?> buildEnumSerializer(SerializationConfig config,
            JavaType type, BeanDescription beanDesc)
        throws JsonMappingException
    {
        /* As per [Issue#24], may want to use alternate shape, serialize as JSON Object.
         * Challenge here is that EnumSerializer does not know how to produce
         * POJO style serialization, so we must handle that special case separately;
         * otherwise pass it to EnumSerializer.
         */
        JsonFormat.Value format = beanDesc.findExpectedFormat(null);
        if (format != null && format.getShape() == JsonFormat.Shape.OBJECT) {
            // one special case: suppress serialization of "getDeclaringClass()"...
            ((BasicBeanDescription) beanDesc).removeProperty("declaringClass");
            // returning null will mean that eventually BeanSerializer gets constructed
            return null;
        }
        @SuppressWarnings("unchecked")
        Class<Enum<?>> enumClass = (Class<Enum<?>>) type.getRawClass();
        JsonSerializer<?> ser = EnumSerializer.construct(enumClass, config, beanDesc, format);
        // [Issue#120]: Allow post-processing
        if (_factoryConfig.hasSerializerModifiers()) {
            for (BeanSerializerModifier mod : _factoryConfig.serializerModifiers()) {
                ser = mod.modifyEnumSerializer(config, type, beanDesc, ser);
            }
        }
        return ser;
    }

    /*
    /**********************************************************
    /* Other helper methods
    /**********************************************************
     */
    
    /**
     * Helper method used to encapsulate details of annotation-based type coercion
     */
    @SuppressWarnings("unchecked")
    protected <T extends JavaType> T modifyTypeByAnnotation(SerializationConfig config,
            Annotated a, T type)
    {
        // first: let's check class for the instance itself:
        Class<?> superclass = config.getAnnotationIntrospector().findSerializationType(a);
        if (superclass != null) {
            try {
                type = (T) type.widenBy(superclass);
            } catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException("Failed to widen type "+type+" with concrete-type annotation (value "+superclass.getName()+"), method '"+a.getName()+"': "+iae.getMessage());
            }
        }
        return modifySecondaryTypesByAnnotation(config, a, type);
    }

    @SuppressWarnings("unchecked")
    protected static <T extends JavaType> T modifySecondaryTypesByAnnotation(SerializationConfig config,
            Annotated a, T type)
    {
        AnnotationIntrospector intr = config.getAnnotationIntrospector();
        // then key class
        if (type.isContainerType()) {
            Class<?> keyClass = intr.findSerializationKeyType(a, type.getKeyType());
            if (keyClass != null) {
                // illegal to use on non-Maps
                if (!(type instanceof MapType)) {
                    throw new IllegalArgumentException("Illegal key-type annotation: type "+type+" is not a Map type");
                }
                try {
                    type = (T) ((MapType) type).widenKey(keyClass);
                } catch (IllegalArgumentException iae) {
                    throw new IllegalArgumentException("Failed to narrow key type "+type+" with key-type annotation ("+keyClass.getName()+"): "+iae.getMessage());
                }
            }
            
            // and finally content class; only applicable to structured types
            Class<?> cc = intr.findSerializationContentType(a, type.getContentType());
            if (cc != null) {
                try {
                    type = (T) type.widenContentsBy(cc);
                } catch (IllegalArgumentException iae) {
                    throw new IllegalArgumentException("Failed to narrow content type "+type+" with content-type annotation ("+cc.getName()+"): "+iae.getMessage());
                }
            }
        }
        return type;
    }

    /**
     * Helper method called to try to find whether there is an annotation in the
     * class that indicates key serializer to use.
     * If so, will try to instantiate key serializer and return it; otherwise returns null.
     */
    protected JsonSerializer<Object> _findKeySerializer(SerializerProvider prov,
            Annotated a)
        throws JsonMappingException
    {
        AnnotationIntrospector intr = prov.getAnnotationIntrospector();
        Object serDef = intr.findKeySerializer(a);
        if (serDef != null) {
            return prov.serializerInstance(a, serDef);
        }
        return null;
    }

    /**
     * Helper method called to try to find whether there is an annotation in the
     * class that indicates content ("value") serializer to use.
     * If so, will try to instantiate key serializer and return it; otherwise returns null.
     */
    protected JsonSerializer<Object> _findContentSerializer(SerializerProvider prov,
            Annotated a)
        throws JsonMappingException
    {
        AnnotationIntrospector intr = prov.getAnnotationIntrospector();
        Object serDef = intr.findContentSerializer(a);
        if (serDef != null) {
            return prov.serializerInstance(a, serDef);
        }
        return null;
    }

    /**
     * @deprecated Since 2.1: use method without 'property'
     */
    @Deprecated
    protected final  boolean usesStaticTyping(SerializationConfig config,
            BeanDescription beanDesc, TypeSerializer typeSer, BeanProperty property)
    {
        return usesStaticTyping(config, beanDesc, typeSer);
    }
    
    /**
     * Helper method to check whether global settings and/or class
     * annotations for the bean class indicate that static typing
     * (declared types)  should be used for properties.
     * (instead of dynamic runtime types).
     * 
     * @since 2.1 (earlier had variant with additional 'property' parameter)
     */
    protected boolean usesStaticTyping(SerializationConfig config,
            BeanDescription beanDesc, TypeSerializer typeSer)
    {
        /* 16-Aug-2010, tatu: If there is a (value) type serializer, we can not force
         *    static typing; that would make it impossible to handle expected subtypes
         */
        if (typeSer != null) {
            return false;
        }
        AnnotationIntrospector intr = config.getAnnotationIntrospector();
        JsonSerialize.Typing t = intr.findSerializationTyping(beanDesc.getClassInfo());
        if (t != null) {
            if (t == JsonSerialize.Typing.STATIC) {
                return true;
            }
        } else {
            if (config.isEnabled(MapperFeature.USE_STATIC_TYPING)) {
                return true;
            }
        }
        return false;
    }

    protected Class<?> _verifyAsClass(Object src, String methodName, Class<?> noneClass)
    {
        if (src == null) {
            return null;
        }
        if (!(src instanceof Class)) {
            throw new IllegalStateException("AnnotationIntrospector."+methodName+"() returned value of type "+src.getClass().getName()+": expected type JsonSerializer or Class<JsonSerializer> instead");
        }
        Class<?> cls = (Class<?>) src;
        if (cls == noneClass || cls == NoClass.class) {
            return null;
        }
        return cls;
    }
}
