/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 *  conditions of the subcomponent's license, as noted in the LICENSE file.
 */
package org.neo4j.ogm.session.delegates;

import org.neo4j.ogm.compiler.CompileContext;
import org.neo4j.ogm.context.EntityGraphMapper;
import org.neo4j.ogm.context.TransientRelationship;
import org.neo4j.ogm.metadata.ClassInfo;
import org.neo4j.ogm.session.Capability;
import org.neo4j.ogm.session.Neo4jSession;
import org.neo4j.ogm.session.event.Event;
import org.neo4j.ogm.session.event.PersistenceEvent;
import org.neo4j.ogm.session.request.RequestExecutor;

import java.lang.reflect.Array;
import java.util.*;

/**
 * @author Vince Bickers
 * @author Luanne Misquitta
 */
public class SaveDelegate implements Capability.Save {

    private final Neo4jSession session;
    private final RequestExecutor requestExecutor;

    public SaveDelegate(Neo4jSession neo4jSession) {
        this.session = neo4jSession;
        requestExecutor = new RequestExecutor(neo4jSession);
    }

    @Override
    public <T> void save(T object) {
        save(object, -1); // default : full tree of changed objects
    }

    @Override
    public <T> void save(T object, int depth) {
        if (object.getClass().isArray() || Iterable.class.isAssignableFrom(object.getClass())) {
            Collection<T> objects;
            if (object.getClass().isArray()) {
                int length = Array.getLength(object);
                objects = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    T arrayElement = (T) Array.get(object, i);
                    objects.add(arrayElement);
                }
            } else {
                objects = (Collection<T>) object;
            }
            List<CompileContext> contexts = new ArrayList<>();
            for (Object element : objects) {
                contexts.add(new EntityGraphMapper(session.metaData(), session.context()).map(element, depth));
            }
            notifySave(contexts, Event.LIFECYCLE.PRE_SAVE);
            requestExecutor.executeSave(contexts);
            notifySave(contexts, Event.LIFECYCLE.POST_SAVE);
        } else {
            ClassInfo classInfo = session.metaData().classInfo(object);
            if (classInfo != null) {
                CompileContext context = new EntityGraphMapper(session.metaData(), session.context()).map(object, depth);
                notifySave(context, Event.LIFECYCLE.PRE_SAVE);
                requestExecutor.executeSave(context);
                notifySave(context, Event.LIFECYCLE.POST_SAVE);
            } else {
                session.warn(object.getClass().getName() + " is not an instance of a persistable class");
            }
        }
    }

    private void notifySave(List<CompileContext> contexts, Event.LIFECYCLE lifecycle) {
        List<Object> affectedObjects = new LinkedList<>();
        Iterator<CompileContext> compileContextIterator = contexts.iterator();
        while (compileContextIterator.hasNext()) {
            CompileContext context = compileContextIterator.next();
            Iterator<Object> affectedObjectsIterator = context.registry().iterator();
            while (affectedObjectsIterator.hasNext()) {
                Object affectedObject = affectedObjectsIterator.next();
                if (!affectedObjects.contains(affectedObject)) {
                    affectedObjects.add(affectedObject);
                }
            }
        }
        notifySave(affectedObjects.iterator(), lifecycle);
    }


    private void notifySave(CompileContext context, Event.LIFECYCLE lifecycle) {
        notifySave(context.registry().iterator(), lifecycle);
    }

    /**
     * Fire save notifications on the affected objects.
     *
     * The affected objects are obtained from one or more {@link CompileContext}s generated by
     * by the {@link org.neo4j.ogm.compiler.Compiler} during a save request.
     *
     * There may be two types of objects.
     *
     * The first type consists of actual {@link org.neo4j.ogm.annotation.NodeEntity}
     * and {@link org.neo4j.ogm.annotation.RelationshipEntity} objects. They are included
     * because their simple properties have changed.
     *
     * Because these objects are the actual domain entities themselves, we can fire the
     * appropriate event directly for them.
     *
     * The second type are {@link TransientRelationship} objects. These objects represent
     * references that have changed between two {@link org.neo4j.ogm.annotation.NodeEntity}
     * objects. They include simple inferred relationships, as well as "rich" relationships
     * backed by instances of {@link org.neo4j.ogm.annotation.RelationshipEntity}.
     *
     * These objects are inspected to obtain the {@link org.neo4j.ogm.annotation.NodeEntity}
     * entities on each side of the relationship. An event is then fired for each of these
     * entities, provided it still exists in the {@link org.neo4j.ogm.context.MappingContext}.
     *
     * {@link TransientRelationship} objects representing "rich" relationships are skipped
     * because they will already be handled in the first type.
     *
     * @param affectedObjectsIterator an {@link Iterator} of objects on which to fire events
     * @param lifecycle
     */
    private void notifySave(Iterator<Object> affectedObjectsIterator, Event.LIFECYCLE lifecycle) {

        while (affectedObjectsIterator.hasNext()) {

            Object affectedObject = affectedObjectsIterator.next();

            if (!(affectedObject instanceof TransientRelationship)) {
                session.notifyListeners(new PersistenceEvent(affectedObject, lifecycle));
            }
            else {
                TransientRelationship tr = (TransientRelationship) affectedObject;
                // don't include RE's, they're already handled elsewhere as 'proper' objects
                if (tr.getRef() <= 0) {

                    // if the source object exists, it's been affected by relationship change
                    // so we need to fire an event
                    if (tr.getSrc() >= 0) {
                        Object object = session.context().getNodeEntity(tr.getSrc());
                        if (object != null) {
                            session.notifyListeners(new PersistenceEvent(object, lifecycle));
                        }
                    }

                    // if the target object exists, it's been affected by relationship change
                    // so we need to fire an event
                    if (tr.getTgt() >= 0) {
                        Object object = session.context().getNodeEntity(tr.getTgt());
                        if (object != null) {
                            session.notifyListeners(new PersistenceEvent(object, lifecycle));
                        }
                    }
                }
            }
        }
    }
}
