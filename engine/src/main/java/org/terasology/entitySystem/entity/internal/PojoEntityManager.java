/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.entitySystem.entity.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.list.TLongList;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityCache;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.entity.SectorManager;
import org.terasology.entitySystem.entity.lifecycleEvents.BeforeDeactivateComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.BeforeEntityCreated;
import org.terasology.entitySystem.entity.lifecycleEvents.BeforeRemoveComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnActivatedComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnAddedComponent;
import org.terasology.entitySystem.entity.lifecycleEvents.OnChangedComponent;
import org.terasology.entitySystem.event.internal.EventSystem;
import org.terasology.entitySystem.metadata.ComponentLibrary;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.entitySystem.prefab.PrefabManager;
import org.terasology.math.geom.Quat4f;
import org.terasology.math.geom.Vector3f;
import org.terasology.persistence.typeHandling.TypeSerializationLibrary;
import org.terasology.protobuf.EntityData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class PojoEntityManager implements EngineEntityManager {
    public static final long NULL_ID = 0;

    private static final Logger logger = LoggerFactory.getLogger(PojoEntityManager.class);

    private long nextEntityId = 1;
    private TLongSet loadedIds = new TLongHashSet();

    private EngineEntityCache globalCache = new PojoEntityCache(this);
    private PojoSectorManager sectorManager = new PojoSectorManager(this);
    private Map<Long, EngineEntityCache> cacheMap = new MapMaker().initialCapacity(1000).makeMap();

    private Set<EntityChangeSubscriber> subscribers = Sets.newLinkedHashSet();
    private Set<EntityDestroySubscriber> destroySubscribers = Sets.newLinkedHashSet();
    private EventSystem eventSystem;
    private PrefabManager prefabManager;
    private ComponentLibrary componentLibrary;

    private RefStrategy refStrategy = new DefaultRefStrategy();

    private TypeSerializationLibrary typeSerializerLibrary;

    public void setTypeSerializerLibrary(TypeSerializationLibrary serializerLibrary) {
        this.typeSerializerLibrary = serializerLibrary;
    }

    public void setComponentLibrary(ComponentLibrary componentLibrary) {
        this.componentLibrary = componentLibrary;
    }

    public void setPrefabManager(PrefabManager prefabManager) {
        this.prefabManager = prefabManager;
    }

    @Override
    public RefStrategy getEntityRefStrategy() {
        return refStrategy;
    }

    @Override
    public EntityCache getGlobalCache () {
        return globalCache;
    }

    @Override
    public void clear() {
        globalCache.clear();
        sectorManager.clear();
        nextEntityId = 1;
        loadedIds.clear();
    }

    @Override
    public EntityBuilder newBuilder() {
        return globalCache.newBuilder();
    }

    @Override
    public EntityBuilder newBuilder(String prefabName) {
        return globalCache.newBuilder(prefabName);
    }

    @Override
    public EntityBuilder newBuilder(Prefab prefab) {
        return globalCache.newBuilder(prefab);
    }

    @Override
    public EntityRef create() {
        return globalCache.create();
    }

    @Override
    public EntityRef createSectorEntity() {
        EntityRef entity = sectorManager.create();
        entity.setScope(EntityData.Entity.Scope.SECTOR);
        assignToCache(entity, sectorManager);
        return entity;
    }

    @Override
    public long createEntity() {
        if (nextEntityId == NULL_ID) {
            nextEntityId++;
        }
        loadedIds.add(nextEntityId);
        return nextEntityId++;
    }

    public long createEntity(EngineEntityCache cache) {
        long id = createEntity();
        cacheMap.put(id, cache);
        return id;
    }

    @Override
    public EntityRef create(Component... components) {
        return globalCache.create(components);
    }

    @Override
    public EntityRef create(Iterable<Component> components) {
        return globalCache.create(components);
    }

    @Override
    public EntityRef create(Iterable<Component> components, boolean sendLifecycleEvents) {
        return globalCache.create(components, sendLifecycleEvents);
    }

    @Override
    public void setEntityRefStrategy(RefStrategy strategy) {
        this.refStrategy = strategy;
    }

    private EntityRef createEntity(Iterable<Component> components) {
        long entityId = createEntity();

        Prefab prefab = null;
        for (Component component : components) {
            if (component instanceof EntityInfoComponent) {
                EntityInfoComponent comp = (EntityInfoComponent) component;
                prefab = comp.parentPrefab;
                break;
            }
        }

        Iterable<Component> finalComponents;
        if (eventSystem != null) {
            BeforeEntityCreated event = new BeforeEntityCreated(prefab, components);
            BaseEntityRef tempRef = refStrategy.createRefFor(entityId, this);
            eventSystem.send(tempRef, event);
            tempRef.invalidate();
            finalComponents = event.getResultComponents();
        } else {
            finalComponents = components;
        }

        for (Component c : finalComponents) {
            globalCache.getComponentStore().put(entityId, c);
        }
        return createEntityRef(entityId);
    }

    @Override
    public EntityRef create(String prefabName) {
        return globalCache.create(prefabName);
    }

    @Override
    public EntityRef create(Prefab prefab, Vector3f position) {
        return globalCache.create(prefab, position);
    }

    @Override
    public EntityRef create(Prefab prefab, Vector3f position, Quat4f rotation) {
        return globalCache.create(prefab, position, rotation);
    }

    @Override
    public EntityRef getEntity(long id) {
        return createEntityRef(id);
    }

    @Override
    public EntityRef create(String prefab, Vector3f position) {
        return globalCache.create(prefab, position);
    }

    @Override
    public EntityRef create(Prefab prefab) {
        return globalCache.create(prefab);
    }

    @Override
    //Todo: Depreciated, maybe remove? Not many uses
    public EntityRef copy(EntityRef other) {
        if (!other.exists()) {
            return EntityRef.NULL;
        }
        List<Component> newEntityComponents = Lists.newArrayList();
        for (Component c : other.iterateComponents()) {
            newEntityComponents.add(componentLibrary.copy(c));
        }
        return globalCache.create(newEntityComponents);
    }

    @Override
    public Map<Class<? extends Component>, Component> copyComponents(EntityRef other) {
        Map<Class<? extends Component>, Component> result = Maps.newHashMap();
        for (Component c : other.iterateComponents()) {
            result.put(c.getClass(), componentLibrary.copy(c));
        }
        return result;
    }

    @Override
    public Iterable<EntityRef> getAllEntities() {
        return Iterables.concat(globalCache.getAllEntities(), sectorManager.getAllEntities());
    }

    @SafeVarargs
    @Override
    public final Iterable<EntityRef> getEntitiesWith(Class<? extends Component>... componentClasses) {
        return Iterables.concat(globalCache.getEntitiesWith(componentClasses),
                sectorManager.getEntitiesWith(componentClasses));
    }

    @Override
    public int getActiveEntityCount() {
        return globalCache.getActiveEntityCount() + sectorManager.getActiveEntityCount();
    }

    @Override
    public EntityRef getExistingEntity(long id) {
        EntityRef entity = globalCache.getExistingEntity(id);
        if (entity == EntityRef.NULL || entity == null) {
            entity = sectorManager.getExistingEntity(id);
        }
        return (entity == null) ? EntityRef.NULL : entity;
    }

    @Override
    public ComponentLibrary getComponentLibrary() {
        return componentLibrary;
    }

    @Override
    public EventSystem getEventSystem() {
        return eventSystem;
    }

    @Override
    public PrefabManager getPrefabManager() {
        return prefabManager;
    }


    /*
     * Engine features
     */


    @Override
    public EntityRef createEntityRefWithId(long entityId) {
        return globalCache.createEntityRefWithId(entityId);
    }

    /**
     * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
     */
    @Override
    public EntityRef createEntityWithoutLifecycleEvents(Iterable<Component> components) {
        EntityRef entity = createEntity(components);
        for (Component component: components) {
            notifyComponentAdded(entity, component.getClass());
        }
        return entity;
    }

    /**
     * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
     */
    @Override
    public EntityRef createEntityWithoutLifecycleEvents(String prefabName) {
        return createEntityWithoutLifecycleEvents(getPrefabManager().getPrefab(prefabName));
    }

    /**
     * Creates the entity without sending any events. The entity life cycle subscriber will however be informed.
     */
    @Override
    public EntityRef createEntityWithoutLifecycleEvents(Prefab prefab) {
        if (prefab != null) {
            List<Component> components = Lists.newArrayList();
            for (Component component : prefab.iterateComponents()) {
                components.add(componentLibrary.copy(component));
            }
            components.add(new EntityInfoComponent(prefab, prefab.isPersisted(), prefab.isAlwaysRelevant()));

            return createEntityWithoutLifecycleEvents(components);
        } else {
            return createEntityWithoutLifecycleEvents(Collections.<Component>emptyList());
        }
    }

    @Override
    public void putEntity(long entityId, BaseEntityRef ref) {
        globalCache.putEntity(entityId, ref);
    }

    @Override
    public ComponentTable getComponentStore() {
        return globalCache.getComponentStore();
    }

    @Override
    public void destroyEntityWithoutEvents(EntityRef entity) {
        globalCache.destroyEntityWithoutEvents(entity);
    }

    @Override
    public EntityRef createEntityWithId(long id, Iterable<Component> components) {
        //TODO: clean this up
        for (Component c : components) {
            if (c instanceof EntityInfoComponent) {
                if (((EntityInfoComponent) c).scope == EntityData.Entity.Scope.SECTOR) {
                    assignToCache(id, sectorManager);
                    EntityRef e = sectorManager.createEntityWithId(id, components);
                    return e;
                }
            }
        }
        return globalCache.createEntityWithId(id, components);
    }

    @Override
    public void subscribeForChanges(EntityChangeSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    @Override
    public void subscribeForDestruction(EntityDestroySubscriber subscriber) {
        destroySubscribers.add(subscriber);
    }

    @Override
    public void unsubscribe(EntityChangeSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    @Override
    public void setEventSystem(EventSystem eventSystem) {
        this.eventSystem = eventSystem;
    }

    @Override
    public TypeSerializationLibrary getTypeSerializerLibrary() {
        return typeSerializerLibrary;
    }

    @Override
    public EngineSectorManager getSectorManager() {
        return sectorManager;
    }

    @Override
    //Todo: include sector entities
    public void deactivateForStorage(EntityRef entity) {
        if (entity.exists()) {
            long entityId = entity.getId();
            if (eventSystem != null) {
                eventSystem.send(entity, BeforeDeactivateComponent.newInstance());
            }
            List<Component> components = globalCache.getComponentStore().getComponentsInNewList(entityId);
            components = Collections.unmodifiableList(components);
            notifyBeforeDeactivation(entity, components);
            for (Component component: components) {
                globalCache.getComponentStore().remove(entityId, component.getClass());
            }
            loadedIds.remove(entityId);
        }
    }

    @Override
    public long getNextId() {
        return nextEntityId;
    }

    @Override
    public void setNextId(long id) {
        nextEntityId = id;
    }


    /*
     * For use by Entity Refs
     */

    /**
     * @param entityId
     * @param componentClass
     * @return Whether the entity has a component of the given type
     */
    @Override
    public boolean hasComponent(long entityId, Class<? extends Component> componentClass) {
        return globalCache.getComponentStore().get(entityId, componentClass) != null
                || sectorManager.hasComponent(entityId, componentClass);
    }

    @Override
    public boolean isExistingEntity(long id) {
        return nextEntityId > id;
    }

    /**
     * @param id
     * @return Whether the entity is currently active
     */
    @Override
    public boolean isActiveEntity(long id) {
        return loadedIds.contains(id);
    }

    /**
     * @param entityId
     * @return An iterable over the components of the given entity
     */
    @Override
    //Todo: implement iterating over multiple caches
    public Iterable<Component> iterateComponents(long entityId) {
        return globalCache.getComponentStore().iterateComponents(entityId);
    }

    @Override
    //Todo: implement destroying in any cache
    public void destroy(long entityId) {
        globalCache.destroy(entityId);
    }

    @Override
    //Todo: implement for any cache
    public void notifyComponentRemovalAndEntityDestruction(long entityId, EntityRef ref) {
        for (Component comp : globalCache.getComponentStore().iterateComponents(entityId)) {
            notifyComponentRemoved(ref, comp.getClass());
        }
        for (EntityDestroySubscriber destroySubscriber : destroySubscribers) {
            destroySubscriber.onEntityDestroyed(ref);
        }
    }

    /**
     * @param entityId
     * @param componentClass
     * @param <T>
     * @return The component of that type owned by the given entity, or null if it doesn't have that component
     */
    @Override
    public <T extends Component> T getComponent(long entityId, Class<T> componentClass) {
        EngineEntityCache cache = cacheMap.get(entityId);
        //Default to the global cache
        if (cache == null) {
            //Todo: this happens a lot during shutdown. Possible concurrency issue?
            //logger.error("Entity {} doesn't have an assigned cache", entityId);
            cache = globalCache;
        }
        return cache.getComponentStore().get(entityId, componentClass);
    }

    /**
     * Adds (or replaces) a component to an entity
     *
     * @param entityId
     * @param component
     * @param <T>
     * @return The added component
     */
    @Override
    //Todo: be able to add to entities in any cache
    public <T extends Component> T addComponent(long entityId, T component) {
        Preconditions.checkNotNull(component);
        EngineEntityCache cache = cacheMap.get(entityId);
        if (cache == null) {
            logger.error("Entity {} doesn't have an assigned cache", entityId);
            cache = globalCache;
        }
        Component oldComponent = cache.getComponentStore().put(entityId, component);

        if (oldComponent != null) {
            logger.error("Adding a component ({}) over an existing component for entity {}", component.getClass(), entityId);
        }
        if (oldComponent == null) {
            notifyComponentAdded(getEntity(entityId), component.getClass());
        } else {
            notifyComponentChanged(getEntity(entityId), component.getClass());
        }
        if (eventSystem != null) {
            EntityRef entityRef = createEntityRef(entityId);
            if (oldComponent == null) {
                eventSystem.send(entityRef, OnAddedComponent.newInstance(), component);
                eventSystem.send(entityRef, OnActivatedComponent.newInstance(), component);
            } else {
                eventSystem.send(entityRef, OnChangedComponent.newInstance(), component);
            }
        }
        return component;
    }

    /**
     * Removes a component from an entity
     *
     * @param entityId
     * @param componentClass
     */
    @Override
    //Todo: be able to remove from entities in any cache
    public <T extends Component> T removeComponent(long entityId, Class<T> componentClass) {
        T component = globalCache.getComponentStore().get(entityId, componentClass);
        if (component != null) {
            if (eventSystem != null) {
                EntityRef entityRef = createEntityRef(entityId);
                eventSystem.send(entityRef, BeforeDeactivateComponent.newInstance(), component);
                eventSystem.send(entityRef, BeforeRemoveComponent.newInstance(), component);
            }
            notifyComponentRemoved(getEntity(entityId), componentClass);
            globalCache.getComponentStore().remove(entityId, componentClass);
        }
        return component;
    }

    /**
     * Saves a component to an entity
     *
     * @param entityId
     * @param component
     */
    @Override
    //Todo: be able to save components for entities in any cache
    public void saveComponent(long entityId, Component component) {
        EngineEntityCache cache = cacheMap.get(entityId);
        if (cache == null) {
            logger.error("Entity {} doesn't have an assigned cache", entityId);
            cache = globalCache;
        }
        Component oldComponent = cache.getComponentStore().put(entityId, component);

        if (oldComponent == null) {
            logger.error("Saving a component ({}) that doesn't belong to this entity {}", component.getClass(), entityId);
        }
        if (eventSystem != null) {
            EntityRef entityRef = createEntityRef(entityId);
            if (oldComponent == null) {
                eventSystem.send(entityRef, OnAddedComponent.newInstance(), component);
                eventSystem.send(entityRef, OnActivatedComponent.newInstance(), component);
            } else {
                eventSystem.send(entityRef, OnChangedComponent.newInstance(), component);
            }
        }
        if (oldComponent == null) {
            notifyComponentAdded(getEntity(entityId), component.getClass());
        } else {
            notifyComponentChanged(getEntity(entityId), component.getClass());
        }
    }


    /*
     * Implementation
     */

    protected void assignToCache(EntityRef ref, EngineEntityCache cache) {
        //Todo: job for the sector manager?
        cacheMap.put(ref.getId(), cache);
    }

    protected void assignToCache(long entityId, EngineEntityCache cache) {
        cacheMap.put(entityId, cache);
    }

    private EntityRef createEntityRef(long entityId) {
        if (entityId == NULL_ID) {
            return EntityRef.NULL;
        }

        //Return existing entity if it exists
        EntityRef existing = getExistingEntity(entityId);
        if(existing != EntityRef.NULL && existing != null) {
            return existing;
        }

        BaseEntityRef newRef = refStrategy.createRefFor(entityId, this);
        globalCache.putEntity(entityId, newRef);
        return newRef;
    }

    public void notifyComponentAdded(EntityRef changedEntity, Class<? extends Component> component) {
        for (EntityChangeSubscriber subscriber : subscribers) {
            subscriber.onEntityComponentAdded(changedEntity, component);
        }
    }

    public void notifyComponentRemoved(EntityRef changedEntity, Class<? extends Component> component) {
        for (EntityChangeSubscriber subscriber : subscribers) {
            subscriber.onEntityComponentRemoved(changedEntity, component);
        }
    }

    public void notifyComponentChanged(EntityRef changedEntity, Class<? extends Component> component) {
        for (EntityChangeSubscriber subscriber : subscribers) {
            subscriber.onEntityComponentChange(changedEntity, component);
        }
    }

    /**
     * This method gets called when the entity gets reactivated. e.g. after storage an entity needs to be reactivated.
     */
    private void notifyReactivation(EntityRef entity, Collection<Component> components) {
        for (EntityChangeSubscriber subscriber : subscribers) {
            subscriber.onReactivation(entity, components);
        }
    }


    /**
     * This method gets called before an entity gets deactivated (e.g. for storage).
     */
    private void notifyBeforeDeactivation(EntityRef entity, Collection<Component> components) {
        for (EntityChangeSubscriber subscriber : subscribers) {
            subscriber.onBeforeDeactivation(entity, components);
        }
    }

    // For testing
    @Override
    @SafeVarargs
    public final int getCountOfEntitiesWith(Class<? extends Component>... componentClasses) {
        return sectorManager.getCountOfEntitiesWith(componentClasses) +
                globalCache.getCountOfEntitiesWith(componentClasses);
    }

    public <T extends Component> Iterable<Map.Entry<EntityRef, T>> listComponents(Class<T> componentClass) {
        TLongObjectIterator<T> iterator = globalCache.getComponentStore().componentIterator(componentClass);
        if (iterator != null) {
            List<Map.Entry<EntityRef, T>> list = new ArrayList<>();
            while (iterator.hasNext()) {
                iterator.advance();
                list.add(new EntityEntry<>(createEntityRef(iterator.key()), iterator.value()));
            }
            return list;
        }
        return Collections.emptyList();
    }

    private static class EntityEntry<T> implements Map.Entry<EntityRef, T> {
        private EntityRef key;
        private T value;

        EntityEntry(EntityRef ref, T value) {
            this.key = ref;
            this.value = value;
        }

        @Override
        public EntityRef getKey() {
            return key;
        }

        @Override
        public T getValue() {
            return value;
        }

        @Override
        public T setValue(T newValue) {
            throw new UnsupportedOperationException();
        }
    }

    private class EntityIterable implements Iterable<EntityRef> {
        private TLongList list;

        EntityIterable(TLongList list) {
            this.list = list;
        }

        @Override
        public Iterator<EntityRef> iterator() {
            return new EntityIterator(list.iterator(), globalCache);
        }
    }


    public boolean registerId(long entityId) {
        if (entityId >= nextEntityId) {
            logger.error("Prevented attempt to create entity with an invalid id.");
            return false;
        }
        loadedIds.add(entityId);
        return true;
    }

    public boolean idLoaded(long entityId) {
        return loadedIds.contains(entityId);
    }

    public void remove(long entityId) {
        loadedIds.remove(entityId);
    }

}
