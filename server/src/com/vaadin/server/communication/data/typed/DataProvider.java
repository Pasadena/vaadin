/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.server.communication.data.typed;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.vaadin.server.AbstractExtension;
import com.vaadin.shared.data.DataProviderClientRpc;
import com.vaadin.shared.data.DataProviderConstants;
import com.vaadin.shared.data.DataRequestRpc;
import com.vaadin.ui.AbstractComponent;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;

/**
 * DataProvider for Collections. This class takes care of sending data objects
 * stored in a Collection from the server-side to the client-side. It uses
 * {@link TypedDataGenerator}s to write a {@link JsonObject} representing each
 * data object.
 * <p>
 * This is an implementation that does not provide any kind of lazy loading. All
 * data is sent to the client-side on the initial client response.
 * 
 * @since
 */
public class DataProvider<T> extends AbstractExtension {

    /**
     * Creates the appropriate type of DataProvider based on the type of
     * Collection provided to the method.
     * <p>
     * <strong>Note:</strong> this method will also extend the given component
     * with the newly created DataProvider. The user should <strong>not</strong>
     * call the {@link #extend(com.vaadin.server.AbstractClientConnector)}
     * method explicitly.
     * 
     * @param data
     *            collection of data objects
     * @param component
     *            component to extend with the data provider
     * @return created data provider
     */
    public static <V> DataProvider<V> create(Collection<V> data,
            AbstractComponent component) {
        DataProvider<V> dataProvider = new DataProvider<V>(data);
        dataProvider.extend(component);
        return dataProvider;
    }

    /**
     * A class for handling currently active data and dropping data that is no
     * longer needed. Data tracking is based on key string provided by
     * {@link DataKeyMapper}.
     * <p>
     * When the {@link DataProvider} is pushing new data to the client-side via
     * {@link DataProvider#pushData(long, Collection)},
     * {@link #addActiveData(Collection)} and {@link #cleanUp(Collection)} are
     * called with the same parameter. In the clean up method any dropped data
     * objects that are not in the given collection will be cleaned up and
     * {@link TypedDataGenerator#destroyData(Object)} will be called for them.
     */
    protected class ActiveDataHandler implements Serializable,
            TypedDataGenerator<T> {

        /**
         * Set of key strings for currently active data objects
         */
        private final Set<String> activeData = new HashSet<String>();

        /**
         * Set of key strings for data objects dropped on the client. This set
         * is used to clean up old data when it's no longer needed.
         */
        private final Set<String> droppedData = new HashSet<String>();

        /**
         * Adds given objects as currently active objects.
         * 
         * @param dataObjects
         *            collection of new active data objects
         */
        public void addActiveData(Collection<T> dataObjects) {
            for (T data : dataObjects) {
                if (!activeData.contains(getKeyMapper().key(data))) {
                    activeData.add(getKeyMapper().key(data));
                }
            }
        }

        /**
         * Executes the data destruction for dropped data that is not sent to
         * the client. This method takes most recently sent data objects in a
         * collection. Doing the clean up like this prevents the
         * {@link ActiveDataHandler} from creating new keys for rows that were
         * dropped but got re-requested by the client-side. In the case of
         * having all data at the client, the collection should be all the data
         * in the back end.
         * 
         * @see DataProvider#pushData(long, Collection)
         * @param dataObjects
         *            collection of most recently sent data to the client
         */
        public void cleanUp(Collection<T> dataObjects) {
            Collection<String> keys = new HashSet<String>();
            for (T data : dataObjects) {
                keys.add(getKeyMapper().key(data));
            }

            // Remove still active rows that were dropped by the client
            droppedData.removeAll(keys);
            // Do data clean up for object no longer needed.
            dropData(droppedData);
            droppedData.clear();
        }

        /**
         * Marks a data object identified by given key string to be dropped.
         * 
         * @param key
         *            key string
         */
        public void dropActiveData(String key) {
            if (activeData.contains(key)) {
                droppedData.add(key);
            }
        }

        /**
         * Returns the collection of all currently active data.
         * 
         * @return collection of active data objects
         */
        public Collection<T> getActiveData() {
            HashSet<T> hashSet = new HashSet<T>();
            for (String key : activeData) {
                hashSet.add(getKeyMapper().get(key));
            }
            return hashSet;
        }

        @Override
        public void generateData(T data, JsonObject jsonObject) {
            // Write the key string for given data object
            jsonObject.put(DataProviderConstants.KEY, getKeyMapper().key(data));
        }

        @Override
        public void destroyData(T data) {
            // Remove from active data set
            activeData.remove(getKeyMapper().key(data));
            // Drop the registered key
            getKeyMapper().remove(data);
        }
    }

    /**
     * Simple implementation of collection data provider communication. All data
     * is sent by server automatically and no data is requested by client.
     */
    protected class DataRequestRpcImpl implements DataRequestRpc {

        @Override
        public void requestRows(int firstRowIndex, int numberOfRows,
                int firstCachedRowIndex, int cacheSize) {
            throw new UnsupportedOperationException(
                    "Collection data provider sends all data from server."
                            + " It does not expect client to request anything.");
        }

        @Override
        public void dropRows(JsonArray rowKeys) {
            // FIXME: What should I do with these?
        }
    }

    private Collection<T> data;
    private Collection<TypedDataGenerator<T>> generators = new LinkedHashSet<TypedDataGenerator<T>>();
    private DataProviderClientRpc rpc;
    // TODO: Allow customizing the used key mapper
    private DataKeyMapper<T> keyMapper = new KeyMapper<T>();
    private ActiveDataHandler handler;

    /**
     * Creates a new DataProvider with the given Collection.
     * 
     * @param data
     *            collection of data to use
     */
    protected DataProvider(Collection<T> data) {
        this.data = data;

        rpc = getRpcProxy(DataProviderClientRpc.class);
        registerRpc(createRpc());
        handler = new ActiveDataHandler();
        addDataGenerator(handler);
    }

    /**
     * Initially we need to push all the data to the client.
     * 
     * TODO: The same is true for unknown size changes.
     */
    @Override
    public void beforeClientResponse(boolean initial) {
        super.beforeClientResponse(initial);

        if (initial) {
            getRpcProxy(DataProviderClientRpc.class).resetSize(data.size());
            pushData(0, data);
        }
    }

    /**
     * Adds a TypedDataGenerator to this DataProvider.
     * 
     * @param generator
     *            typed data generator
     */
    public void addDataGenerator(TypedDataGenerator<T> generator) {
        generators.add(generator);
    }

    /**
     * Removes a TypedDataGenerator from this DataProvider.
     * 
     * @param generator
     *            typed data generator
     */
    public void removeDataGenerator(TypedDataGenerator<T> generator) {
        generators.add(generator);
    }

    /**
     * Sends given data collection to the client-side.
     * 
     * @param firstIndex
     *            first index of pushed data
     * @param data
     *            data objects to send as an iterable
     */
    protected void pushData(long firstIndex, Collection<T> data) {
        JsonArray dataArray = Json.createArray();

        int i = 0;
        for (T item : data) {
            dataArray.set(i++, getDataObject(item));
        }

        rpc.setData(firstIndex, dataArray);
        handler.addActiveData(data);
        handler.cleanUp(data);
    }

    /**
     * Creates the JsonObject for given item. This method calls all data
     * generators for this item.
     * 
     * @param item
     *            item to be made into a json object
     * @return json object representing the item
     */
    protected JsonObject getDataObject(T item) {
        JsonObject dataObject = Json.createObject();

        for (TypedDataGenerator<T> generator : generators) {
            generator.generateData(item, dataObject);
        }

        return dataObject;
    }

    /**
     * Drops data objects identified by given keys from memory. This will invoke
     * {@link TypedDataGenerator#destroyData} for each of those objects.
     * 
     * @param droppedKeys
     *            collection of dropped keys
     */
    private void dropData(Collection<String> droppedKeys) {
        for (String key : droppedKeys) {
            assert key != null : "Bookkeepping failure. Dropping a null key";

            T data = getKeyMapper().get(key);
            assert data != null : "Bookkeepping failure. No data object to match key";

            for (TypedDataGenerator<T> g : generators) {
                g.destroyData(data);
            }
        }
    }

    /**
     * Gets the {@link DataKeyMapper} instance used by this {@link DataProvider}
     * 
     * @return key mapper
     */
    public DataKeyMapper<T> getKeyMapper() {
        return keyMapper;
    }

    /**
     * Creates an instance of DataRequestRpc. By default it is
     * {@link DataRequestRpcImpl}.
     * 
     * @return data request rpc implementation
     */
    protected DataRequestRpc createRpc() {
        return new DataRequestRpcImpl();
    }

    /**
     * Informs the DataProvider that an item has been added. It is assumed to be
     * the last item in the collection.
     * 
     * @param item
     *            item added to collection
     */
    public void add(T item) {
        rpc.add(getDataObject(item));
    }

    /**
     * Informs the DataProvider that an item has been removed.
     * 
     * @param item
     *            item removed from collection
     */
    public void remove(T item) {
        rpc.drop(getDataObject(item));
    }

}