// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.iceberg.hadoop;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Database;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.IcebergCatalog;
import com.starrocks.connector.iceberg.IcebergCatalogType;
import com.starrocks.connector.iceberg.cost.IcebergMetricsReporter;
import com.starrocks.connector.iceberg.io.IcebergCachingFileIO;
import com.starrocks.connector.share.iceberg.IcebergAwsClientFactory;
import com.starrocks.qe.ConnectContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.aws.AwsProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.starrocks.connector.ConnectorTableId.CONNECTOR_ID_GENERATOR;
import static com.starrocks.connector.iceberg.IcebergCatalogProperties.ICEBERG_CUSTOM_PROPERTIES_PREFIX;
import static com.starrocks.connector.iceberg.IcebergMetadata.LOCATION_PROPERTY;
import static org.apache.iceberg.CatalogProperties.WAREHOUSE_LOCATION;

public class IcebergHadoopCatalog implements IcebergCatalog {
    private static final Logger LOG = LogManager.getLogger(IcebergHadoopCatalog.class);

    private final Configuration conf;
    private final HadoopCatalog delegate;

    public IcebergHadoopCatalog(String name, Configuration conf, Map<String, String> properties) {
        this.conf = conf;
        Map<String, String> copiedProperties = Maps.newHashMap(properties);

        properties.forEach((key, value) -> {
            String newKey = key.toLowerCase();
            if (newKey.startsWith(ICEBERG_CUSTOM_PROPERTIES_PREFIX)) {
                newKey = newKey.substring(ICEBERG_CUSTOM_PROPERTIES_PREFIX.length());
                copiedProperties.put(newKey, value);
            }
        });

        copiedProperties.put(CatalogProperties.FILE_IO_IMPL, IcebergCachingFileIO.class.getName());
        copiedProperties.put(CatalogProperties.METRICS_REPORTER_IMPL, IcebergMetricsReporter.class.getName());
        copiedProperties.put(AwsProperties.CLIENT_FACTORY, IcebergAwsClientFactory.class.getName());

        if (!copiedProperties.containsKey(WAREHOUSE_LOCATION)) {
            throw new IllegalArgumentException(String.format("Iceberg hadoop catalog must set warehouse location (\"%s\" = \"s3://path/to/warehouse\").",
                    ICEBERG_CUSTOM_PROPERTIES_PREFIX + WAREHOUSE_LOCATION));
        }

        delegate = (HadoopCatalog) CatalogUtil.loadCatalog(HadoopCatalog.class.getName(), name, copiedProperties, conf);
    }

    @Override
    public IcebergCatalogType getIcebergCatalogType() {
        return IcebergCatalogType.HADOOP_CATALOG;
    }

    @Override
    public Table getTable(ConnectContext context, String dbName, String tableName) throws StarRocksConnectorException {
        return delegate.loadTable(TableIdentifier.of(dbName, tableName));
    }

    @Override
    public boolean tableExists(ConnectContext context, String dbName, String tableName) throws StarRocksConnectorException {
        return delegate.tableExists(TableIdentifier.of(dbName, tableName));
    }

    @Override
    public List<String> listAllDatabases(ConnectContext context) {
        return delegate.listNamespaces().stream()
                .map(ns -> ns.level(0))
                .collect(Collectors.toList());
    }

    @Override
    public void createDB(ConnectContext context, String dbName, Map<String, String> properties) {
        properties = properties == null ? new HashMap<>() : properties;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equalsIgnoreCase(LOCATION_PROPERTY)) {
                try {
                    URI uri = new Path(value).toUri();
                    FileSystem fileSystem = FileSystem.get(uri, conf);
                    fileSystem.exists(new Path(value));
                } catch (Exception e) {
                    LOG.error("Invalid location URI: {}", value, e);
                    throw new StarRocksConnectorException("Invalid location URI: %s. msg: %s", value, e.getMessage());
                }
            } else {
                throw new IllegalArgumentException("Unrecognized property: " + key);
            }
        }
        Namespace ns = Namespace.of(dbName);
        delegate.createNamespace(ns, properties);
    }

    @Override
    public void dropDB(ConnectContext context, String dbName) throws MetaNotFoundException {
        Database database;
        try {
            database = getDB(context, dbName);
        } catch (Exception e) {
            LOG.error("Failed to access database {}", dbName, e);
            throw new MetaNotFoundException("Failed to access database " + dbName);
        }

        if (database == null) {
            throw new MetaNotFoundException("Not found database " + dbName);
        }

        String dbLocation = database.getLocation();
        if (Strings.isNullOrEmpty(dbLocation)) {
            throw new MetaNotFoundException("Database location is empty");
        }

        delegate.dropNamespace(Namespace.of(dbName));
    }

    @Override
    public Database getDB(ConnectContext context, String dbName) {
        Map<String, String> dbMeta = delegate.loadNamespaceMetadata(Namespace.of(dbName));
        Preconditions.checkNotNull(dbMeta.get(LOCATION_PROPERTY), "Database " + dbName + " doesn't exist location");
        return new Database(CONNECTOR_ID_GENERATOR.getNextId().asInt(), dbName, dbMeta.get(LOCATION_PROPERTY));
    }

    @Override
    public List<String> listTables(ConnectContext context, String dbName) {
        List<TableIdentifier> tableIdentifiers = delegate.listTables(Namespace.of(dbName));
        return tableIdentifiers.stream().map(TableIdentifier::name).collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public boolean createTable(
            ConnectContext context,
            String dbName,
            String tableName,
            Schema schema,
            PartitionSpec partitionSpec,
            String location,
            Map<String, String> properties) {
        Table nativeTable = delegate.buildTable(TableIdentifier.of(dbName, tableName), schema)
                .withLocation(location)
                .withPartitionSpec(partitionSpec)
                .withProperties(properties)
                .create();

        return nativeTable != null;
    }

    @Override
    public boolean dropTable(ConnectContext context, String dbName, String tableName, boolean purge) {
        return delegate.dropTable(TableIdentifier.of(dbName, tableName), purge);
    }

    @Override
    public void renameTable(ConnectContext context, String dbName, String tblName, String newTblName)
            throws StarRocksConnectorException {
        delegate.renameTable(TableIdentifier.of(dbName, tblName), TableIdentifier.of(dbName, newTblName));
    }

    @Override
    public void deleteUncommittedDataFiles(List<String> fileLocations) {
        if (fileLocations.isEmpty()) {
            return;
        }

        URI uri = new Path(fileLocations.get(0)).toUri();
        try {
            FileSystem fileSystem = FileSystem.get(uri, conf);
            for (String location : fileLocations) {
                Path path = new Path(location);
                fileSystem.delete(path, false);
            }
        } catch (Exception e) {
            LOG.error("Failed to delete uncommitted files", e);
        }
    }

    public String toString() {
        return delegate.toString();
    }
}
