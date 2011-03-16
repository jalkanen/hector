package me.prettyprint.cassandra.service.template;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prettyprint.cassandra.model.HSlicePredicate;
import me.prettyprint.cassandra.model.thrift.ThriftConverter;
import me.prettyprint.cassandra.serializers.SerializerTypeInferer;
import me.prettyprint.cassandra.service.Operation;
import me.prettyprint.cassandra.service.OperationType;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.exceptions.HectorException;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.CountQuery;
import me.prettyprint.hector.api.query.QueryResult;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;

import com.google.common.collect.Iterators;

/**
 * This applies a Template Method pattern, much like Spring's JdbcTemplate, to
 * Cassandra. The ColumnFamilyTemplate instance maintains many of the fields in
 * common between various query/update operations so that they do not need to be
 * constantly passed for every operation on the column family. These include the
 * keyspace, column family name, key serializer, and the column name serializer
 * (for standard column name or the super column name).
 * 
 * The Java generic types of the ColumnFamilyTemplate class itself are limited to
 * the key and column name type. It defers the generic types for super column
 * child types to the individual update/query operation.
 * 
 * @author david
 * @author zznate
 * @param <K>
 *          The column family key type
 * @param <N>
 *          The column family name type 
 */
public class ColumnFamilyTemplate<K, N> extends AbstractColumnFamilyTemplate<K, N> {
  
  public ColumnFamilyTemplate(Keyspace keyspace, String columnFamily,
      Serializer<K> keySerializer, Serializer<N> topSerializer) {
    super(keyspace, columnFamily, keySerializer, topSerializer);
  }

  public ColumnFamilyTemplate(Keyspace keyspace, String columnFamily,
      Serializer<K> keySerializer, Serializer<N> topSerializer,
      Mutator<K> mutator) {
    super(keyspace, columnFamily, keySerializer, topSerializer, mutator);
  }

  // Just so method chaining will return this type instead of the parent class
  // for operations down the chain
  public ColumnFamilyTemplate<K, N> setBatched(boolean batched) {
    super.setBatched(batched);
    return this;
  }

  // Just so method chaining will return this type instead of the parent class
  // for operations down the chain
  public ColumnFamilyTemplate<K, N> setMutator(Mutator<K> mutator) {
    super.setMutator(mutator);
    return this;
  }

  public ColumnFamilyUpdater<K, N> createUpdater(K key) {
    ColumnFamilyUpdater<K, N> updater = new ColumnFamilyUpdater<K, N>();
    updater.key = key;
    updater.template = this;
    return updater;
  }
  
  public void update(ColumnFamilyUpdater<K, N> updater) {
    updater.update();
    executeIfNotBatched();
  }
  
  /**
   * Updates values in a standard column family in the row specified by key.
   * 
   * @param key
   *          the row key
   * @param updater
   *          the object performing updates of the current row
   */
  public void update(K key, ColumnFamilyUpdater<K, N> updater) {
    updater.template = this;
    updater.key = key;
    update(updater);
  }
  
  
  /**
   * Checks if there are any columns at a row specified by key in a standard
   * column family
   * 
   * @param key
   * @return true if columns exist
   */
  public boolean isColumnsExist(K key) {
    return countColumns(key) > 0;
  }

  /**
   * @param key
   * @return the number of columns in a standard column family at the specified
   *         row key
   */
  @SuppressWarnings("unchecked")
  public int countColumns(K key) {
    return countColumns(key, (N) ALL_COLUMNS_START, (N) ALL_COLUMNS_END,
        ALL_COLUMNS_COUNT);
  }

  /**
   * Counts columns in the specified range of a standard column family
   * 
   * @param key
   * @param start
   * @param end
   * @param max
   * @return
   */
  public int countColumns(K key, N start, N end, int max) {
    CountQuery<K, N> query = HFactory.createCountQuery(keyspace, keySerializer,
        topSerializer);
    query.setKey(key);
    query.setColumnFamily(columnFamily);
    query.setRange(start, end, max);
    return query.execute().get();
  }

  public ColumnFamilyResultWrapper<K, N> queryColumns(K key) {
    return doExecuteSlice(key, null);
  }

  public ColumnFamilyResultWrapper<K, N> queryColumns(Iterable<K> keys) {
    return doExecuteMultigetSlice(keys, null);
  }
  
  @SuppressWarnings("unchecked")
  public <T> T queryColumns(K key, ColumnFamilyRowMapper<K, N, T> mapper) {
    return queryColumns(key, (N) ALL_COLUMNS_START, (N) ALL_COLUMNS_END, mapper);
  }

  /**
   * Queries a range of columns at the given key and maps them to an object of
   * type OBJ using the given mapping object
   * 
   * @param <T>
   * @param key
   * @param start
   * @param end
   * @param mapper
   * @return
   */
  public <T> T queryColumns(K key, N start, N end,
      ColumnFamilyRowMapper<K, N, T> mapper) {
    HSlicePredicate<N> predicate = new HSlicePredicate<N>(topSerializer);
    predicate.setStartOn(start);
    predicate.setEndOn(end);
    predicate.setCount(100);
    return executeSliceQuery(key, predicate, mapper);
  }

  /**
   * Queries all columns at a given key and maps them to an object of type OBJ
   * using the given mapping object
   * 
   * @param <T>
   * @param key
   * @param columns
   * @param mapper
   * @return
   */
  @SuppressWarnings("unchecked")
  public <T> T queryColumns(K key, List<N> columns,
      ColumnFamilyRowMapper<K, N, T> mapper) {
    HSlicePredicate<N> predicate = new HSlicePredicate<N>(topSerializer);
    predicate.setColumnNames(columns);        
    return executeSliceQuery(key, predicate, mapper);
  }

  private <T> T executeSliceQuery(K key, HSlicePredicate predicate, ColumnFamilyRowMapper<K, N, T> mapper) {
    return mapper.mapRow(doExecuteSlice(key,predicate));
  }


/*  @SuppressWarnings("unchecked")
  public <T> ColumnFamilyResultsIterator<K, T> queryColumns(Iterable<K> keys,
      ColumnFamilyRowMapper<K, N, T> mapper) {
    return queryColumns(keys, (N) ALL_COLUMNS_START, (N) ALL_COLUMNS_END,
        mapper);
  }

  public ColumnFamilyResults<K, N> queryColumns(Iterable<K> keys,
      N start, N end, ColumnFamilyRowMapper<K, N, T> mapper) {
    MultigetSliceQuery<K, N, ByteBuffer> query = createMultigetSliceQuery(keys);
    query.setRange(start, end, false, ALL_COLUMNS_COUNT);
    return executeMultigetSliceQuery(keys, query, mapper);
  }

  @SuppressWarnings("unchecked")
  public ColumnFamilyResults<K, N> queryColumns(Iterable<K> key,
      List<N> columns, ColumnFamilyRowMapper<K, N> mapper) {

    HSlicePredicate<N> predicate = new HSlicePredicate<N>(topSerializer);
    predicate.setColumnNames(columns);
    return mapper.mapRow(doExecuteMultigetSlice(key, predicate));
  }
*/


  @SuppressWarnings("unchecked")
  public <V> HColumn<N, V> querySingleColumn(K key, N columnName,
      Class<V> valueClass) {
    return querySingleColumn(key, columnName,
        (Serializer<V>) SerializerTypeInferer.getSerializer(valueClass));
  }

  public <V> HColumn<N, V> querySingleColumn(K key, N columnName,
      Serializer<V> valueSerializer) {
    ColumnQuery<K, N, V> query = HFactory.createColumnQuery(keyspace,
        keySerializer, topSerializer, valueSerializer);
    query.setColumnFamily(columnFamily);
    query.setKey(key);
    query.setName(columnName);
    QueryResult<HColumn<N, V>> result = query.execute();
    return result != null ? result.get() : null;
  }
  
  private ColumnFamilyResultWrapper<K, N> doExecuteSlice(final K key, final HSlicePredicate<N> workingSlicePredicate) {
    
    return keyspace.doExecuteOperation(new Operation<ColumnFamilyResultWrapper<K, N>>(OperationType.READ) {
      @Override
      public ColumnFamilyResultWrapper<K, N> execute(Cassandra.Client cassandra) throws HectorException {
        Map<ByteBuffer,List<ColumnOrSuperColumn>> cosc = new LinkedHashMap<ByteBuffer, List<ColumnOrSuperColumn>>();
        try {          
          
          ByteBuffer sKey = keySerializer.toByteBuffer(key);
          cosc.put(sKey, cassandra.get_slice(sKey, columnParent,
              (workingSlicePredicate == null ? activeSlicePredicate.setColumnNames(columnValueSerializers.keySet()).toThrift() : workingSlicePredicate.toThrift()), 
            ThriftConverter.consistencyLevel(consistencyLevelPolicy.get(operationType))));
          
        } catch (Exception e) {
          throw exceptionsTranslator.translate(e);
        }        

        return new ColumnFamilyResultWrapper<K, N>(keySerializer, topSerializer, cosc);
      }
    }).get();
  }
  
  private ColumnFamilyResultWrapper<K, N> doExecuteMultigetSlice(final Iterable<K> keys, final HSlicePredicate<N> workingSlicePredicate) {
    
    return keyspace.doExecuteOperation(new Operation<ColumnFamilyResultWrapper<K, N>>(OperationType.READ) {
      @Override
      public ColumnFamilyResultWrapper<K, N> execute(Cassandra.Client cassandra) throws HectorException {
        Map<ByteBuffer,List<ColumnOrSuperColumn>> cosc;
        try {          
          List<K> keyList = new ArrayList<K>();
          Iterators.addAll(keyList, keys.iterator());
          cosc = cassandra.multiget_slice(keySerializer.toBytesList(keyList), columnParent,
              (workingSlicePredicate == null ? activeSlicePredicate.setColumnNames(columnValueSerializers.keySet()).toThrift() : workingSlicePredicate.toThrift()), 
            ThriftConverter.consistencyLevel(consistencyLevelPolicy.get(operationType)));
          
        } catch (Exception e) {
          throw exceptionsTranslator.translate(e);
        }        

        return new ColumnFamilyResultWrapper<K, N>(keySerializer, topSerializer, cosc);
      }
    }).get();
  }

}
