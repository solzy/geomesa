/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/


package org.locationtech.geomesa.accumulo.data

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

import org.apache.accumulo.core.client._
import org.apache.accumulo.core.client.admin.TableOperations
import org.apache.accumulo.core.security.Authorizations
import org.apache.hadoop.security.UserGroupInformation
import org.geotools.data.Query
import org.locationtech.geomesa.accumulo._
import org.locationtech.geomesa.accumulo.audit.AccumuloAuditService
import org.locationtech.geomesa.accumulo.data.stats._
import org.locationtech.geomesa.accumulo.index.AccumuloAttributeIndex.AttributeSplittable
import org.locationtech.geomesa.accumulo.index._
import org.locationtech.geomesa.accumulo.iterators.ProjectVersionIterator
import org.locationtech.geomesa.accumulo.security.AccumuloAuthsProvider
import org.locationtech.geomesa.accumulo.util.ZookeeperLocking
import org.locationtech.geomesa.index.api.GeoMesaFeatureIndex
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.GeoMesaDataStoreConfig
import org.locationtech.geomesa.index.geotools.{GeoMesaFeatureCollection, GeoMesaFeatureSource}
import org.locationtech.geomesa.index.metadata.{GeoMesaMetadata, MetadataStringSerializer}
import org.locationtech.geomesa.index.utils.Explainer
import org.locationtech.geomesa.utils.audit.{AuditProvider, AuditReader, AuditWriter}
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.index.IndexMode
import org.locationtech.geomesa.utils.index.IndexMode.IndexMode
import org.locationtech.geomesa.utils.stats.{IndexCoverage, Stat}
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter

import scala.collection.JavaConversions._
import scala.util.control.NonFatal

/**
 * This class handles DataStores which are stored in Accumulo Tables. To be clear, one table may
 * contain multiple features addressed by their featureName.
 *
 * @param connector Accumulo connector
 * @param config configuration values
 */
class AccumuloDataStore(val connector: Connector, override val config: AccumuloDataStoreConfig)
    extends AccumuloDataStoreType(config) with ZookeeperLocking {

  override val metadata = new AccumuloBackedMetadata(connector, config.catalog, MetadataStringSerializer)

  private val oldMetadata = new SingleRowAccumuloMetadata(metadata)

  override def manager: AccumuloIndexManagerType = AccumuloFeatureIndex

  private val statsTable = GeoMesaFeatureIndex.formatSharedTableName(config.catalog, "stats")
  override val stats = new AccumuloGeoMesaStats(this, statsTable, config.generateStats)

  // If on a secured cluster, create a thread to periodically renew Kerberos tgt
  val kerberosTgtRenewer: Option[ScheduledExecutorService] = try {
    if (UserGroupInformation.isSecurityEnabled) {
      val executor = Executors.newSingleThreadScheduledExecutor()
      executor.scheduleAtFixedRate(
        new Runnable {
          def run(): Unit = {
            try {
              logger.info(s"Checking whether TGT needs renewing for ${UserGroupInformation.getCurrentUser}")
              logger.debug(s"Logged in from keytab? ${UserGroupInformation.getCurrentUser.isFromKeytab}")
              UserGroupInformation.getCurrentUser.checkTGTAndReloginFromKeytab()
            } catch {
              case NonFatal(e) => logger.warn("Error checking and renewing TGT", e)
            }
          }
        }, 0, 10, TimeUnit.MINUTES)
      Some(executor)
    } else { None }
  } catch {
    case e: Throwable => logger.error("Error checking for hadoop security", e); None
  }

  // some convenience operations

  def auths: Authorizations = config.authProvider.getAuthorizations
  val tableOps: TableOperations = connector.tableOperations()

  override def delete(): Unit = {
    // note: don't delete the query audit table
    val auditTable = config.audit.map(_._1.asInstanceOf[AccumuloAuditService].table).toSeq
    val tables = getTypeNames.flatMap(getAllTableNames).distinct.filterNot(_ == auditTable)
    tables.par.filter(tableOps.exists).foreach(tableOps.delete)
  }

  override def getAllTableNames(typeName: String): Seq[String] = {
    val others = Seq(statsTable) ++ config.audit.map(_._1.asInstanceOf[AccumuloAuditService].table).toSeq
    super.getAllTableNames(typeName) ++ others
  }

  // data store hooks

  override protected def createFeatureWriterAppend(sft: SimpleFeatureType,
                                                   indices: Option[Seq[AccumuloFeatureIndexType]]): AccumuloFeatureWriterType =
    new AccumuloAppendFeatureWriter(sft, this, indices, config.defaultVisibilities)

  override protected def createFeatureWriterModify(sft: SimpleFeatureType,
                                                   indices: Option[Seq[AccumuloFeatureIndexType]],
                                                   filter: Filter): AccumuloFeatureWriterType =
    new AccumuloModifyFeatureWriter(sft, this, indices, config.defaultVisibilities, filter)

  override protected def createFeatureCollection(query: Query, source: GeoMesaFeatureSource): GeoMesaFeatureCollection =
    new AccumuloFeatureCollection(source, query)

  override protected def createQueryPlanner(): AccumuloQueryPlannerType = new AccumuloQueryPlanner(this)

  override protected def getIteratorVersion: Set[String] = {
    def getVersions(table: String): Set[String] = {
      val scanner = connector.createScanner(table, new Authorizations())
      try {
        ProjectVersionIterator.scanProjectVersion(scanner)
      } catch {
        case NonFatal(e) => Set("unavailable")
      } finally {
        scanner.close()
      }
    }
    val ops = connector.tableOperations()
    getTypeNames.toSet.flatMap(getAllTableNames).filter(ops.exists).flatMap(getVersions)
  }

  @throws(classOf[IllegalArgumentException])
  override protected def validateNewSchema(sft: SimpleFeatureType): Unit = {
    // check for old enabled indices and re-map them
    SimpleFeatureTypes.Configs.ENABLED_INDEX_OPTS.drop(1).find(sft.getUserData.containsKey).foreach { key =>
      sft.getUserData.put(SimpleFeatureTypes.Configs.ENABLED_INDICES, sft.getUserData.remove(key))
    }

    super.validateNewSchema(sft)

    if (sft.isTableSharing &&
        getTypeNames.map(getSchema).exists(t => t.isTableSharing && t.isLogicalTime != sft.isLogicalTime)) {
      logger.warn(s"Trying to create schema '${sft.getTypeName}' using " +
          s"${if (sft.isLogicalTime) "logical" else "system"} time, but shared tables already exist with " +
          s"${if (sft.isLogicalTime) "system" else "logical"} time. Disabling table sharing to force creation " +
          "of new tables")
      sft.setTableSharing(false)
    }

    // default to full indices if ambiguous - replace 'index=true' with explicit 'index=full'
    sft.getAttributeDescriptors.foreach { descriptor =>
      import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.AttributeOptions.OPT_INDEX
      descriptor.getUserData.get(OPT_INDEX) match {
        case s: String if java.lang.Boolean.parseBoolean(s) => descriptor.getUserData.put(OPT_INDEX, IndexCoverage.FULL.toString)
        case _ => // no-op
      }
    }
  }

  override protected def onSchemaCreated(sft: SimpleFeatureType): Unit = {
    super.onSchemaCreated(sft)
    // configure the stats combining iterator on the table for this sft
    stats.configureStatCombiner(connector, sft)
  }

  override protected def onSchemaUpdated(sft: SimpleFeatureType, previous: SimpleFeatureType): Unit = {
    import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors.RichAttributeDescriptor
    // check for newly indexed attributes and re-configure the splits
    val previousAttrIndices = previous.getAttributeDescriptors.collect { case d if d.isIndexed => d.getLocalName }
    if (sft.getAttributeDescriptors.exists(d => d.isIndexed && !previousAttrIndices.contains(d.getLocalName))) {
      manager.indices(sft, IndexMode.Any).foreach {
        case s: AttributeSplittable => s.configureSplits(sft, this)
        case _ => // no-op
      }
    }
    // configure the stats combining iterator on the table for this sft
    stats.configureStatCombiner(connector, sft)
  }

  override def getQueryPlan(query: Query,
                            index: Option[AccumuloFeatureIndexType],
                            explainer: Explainer): Seq[AccumuloQueryPlan] =
    super.getQueryPlan(query, index, explainer).asInstanceOf[Seq[AccumuloQueryPlan]]

  // extensions and back-compatibility checks for core data store methods

  override def getTypeNames: Array[String] = super.getTypeNames ++ oldMetadata.getFeatureTypes

  override def getSchema(typeName: String): SimpleFeatureType = {
    import GeoMesaMetadata.{ATTRIBUTES_KEY, SCHEMA_ID_KEY, STATS_GENERATION_KEY, VERSION_KEY}
    import SimpleFeatureTypes.Configs.{ENABLED_INDEX_OPTS, ENABLED_INDICES}
    import SimpleFeatureTypes.InternalConfigs.{INDEX_VERSIONS, SCHEMA_VERSION_KEY}

    var sft = super.getSchema(typeName)

    if (sft == null) {
      // check for old-style metadata and re-write it if necessary
      if (oldMetadata.getFeatureTypes.contains(typeName)) {
        val lock = acquireCatalogLock()
        try {
          if (oldMetadata.getFeatureTypes.contains(typeName)) {
            oldMetadata.migrate(typeName)
            new SingleRowAccumuloMetadata[Stat](stats.metadata).migrate(typeName)
          }
        } finally {
          lock.release()
        }
        sft = super.getSchema(typeName)
      }
    }
    if (sft != null) {
      // back compatible check for index versions
      if (!sft.getUserData.contains(INDEX_VERSIONS)) {
        // back compatible check if user data wasn't encoded with the sft
        if (!sft.getUserData.containsKey(SCHEMA_VERSION_KEY)) {
          metadata.read(typeName, "dtgfield").foreach(sft.setDtgField)
          sft.getUserData.put(SCHEMA_VERSION_KEY, metadata.readRequired(typeName, VERSION_KEY))

          // If no data is written, we default to 'false' in order to support old tables.
          if (metadata.read(typeName, "tables.sharing").exists(_.toBoolean)) {
            sft.setTableSharing(true)
            // use schema id if available or fall back to old type name for backwards compatibility
            val prefix = metadata.read(typeName, SCHEMA_ID_KEY).getOrElse(s"${sft.getTypeName}~")
            sft.setTableSharingPrefix(prefix)
          } else {
            sft.setTableSharing(false)
            sft.setTableSharingPrefix("")
          }
          ENABLED_INDEX_OPTS.foreach { i =>
            metadata.read(typeName, i).foreach(e => sft.getUserData.put(ENABLED_INDICES, e))
          }
        }

        // set the enabled indices
        sft.setIndices(AccumuloDataStore.getEnabledIndices(sft))

        // store the metadata and reload the sft again to validate indices
        metadata.insert(typeName, ATTRIBUTES_KEY, SimpleFeatureTypes.encodeType(sft, includeUserData = true))
        sft = super.getSchema(typeName)
      }

      // back compatibility check for stat configuration
      if (config.generateStats && metadata.read(typeName, STATS_GENERATION_KEY).isEmpty) {
        // configure the stats combining iterator - we only use this key for older data stores
        val configuredKey = "stats-configured"
        if (!metadata.read(typeName, configuredKey).contains("true")) {
          val lock = acquireCatalogLock()
          try {
            if (!metadata.read(typeName, configuredKey, cache = false).contains("true")) {
              stats.configureStatCombiner(connector, sft)
              metadata.insert(typeName, configuredKey, "true")
            }
          } finally {
            lock.release()
          }
        }
        // kick off asynchronous stats run for the existing data
        // this may get triggered more than once, but should only run one time
        val statsRunner = new StatsRunner(this)
        statsRunner.submit(sft)
        statsRunner.close()
      }
    }

    sft
  }

  override def dispose(): Unit = {
    super.dispose()
    kerberosTgtRenewer.foreach( _.shutdown() )
  }

}

object AccumuloDataStore {

  /**
    * Reads the indices configured using SimpleFeatureTypes.ENABLED_INDICES, or the
    * default indices for the schema version
    *
    * @param sft simple feature type
    * @return sequence of index (name, version)
    */
  private def getEnabledIndices(sft: SimpleFeatureType): Seq[(String, Int, IndexMode)] = {
    val marked: Seq[String] = SimpleFeatureTypes.Configs.ENABLED_INDEX_OPTS.map(sft.getUserData.get).find(_ != null) match {
      case None => AccumuloFeatureIndex.AllIndices.map(_.name).distinct
      case Some(enabled) =>
        val e = enabled.toString.split(",").map(_.trim).filter(_.length > 0)
        // check for old attribute index name
        if (e.contains("attr_idx")) {  e :+ AttributeIndex.name } else { e }
    }
    AccumuloFeatureIndex.getDefaultIndices(sft).collect {
      case i if marked.contains(i.name) => (i.name, i.version, IndexMode.ReadWrite)
    }
  }
}

/**
  * Configuration options for AccumuloDataStore
  *
  * @param catalog table in Accumulo used to store feature type metadata
  * @param defaultVisibilities default visibilities applied to any data written
  * @param generateStats write stats on data during ingest
  * @param authProvider provides the authorizations used to access data
  * @param audit optional implementations to audit queries
  * @param queryTimeout optional timeout (in millis) before a long-running query will be terminated
  * @param looseBBox sacrifice some precision for speed
  * @param caching cache feature results - WARNING can use large amounts of memory
  * @param writeThreads numer of threads used for writing
  * @param queryThreads number of threads used per-query
  * @param recordThreads number of threads used to join against the record table. Because record scans
  *                      are single-row ranges, increasing this too much can cause performance to decrease
  */
case class AccumuloDataStoreConfig(catalog: String,
                                   defaultVisibilities: String,
                                   generateStats: Boolean,
                                   authProvider: AccumuloAuthsProvider,
                                   audit: Option[(AuditWriter with AuditReader, AuditProvider, String)],
                                   queryTimeout: Option[Long],
                                   looseBBox: Boolean,
                                   caching: Boolean,
                                   writeThreads: Int,
                                   queryThreads: Int,
                                   recordThreads: Int,
                                   namespace: Option[String]) extends GeoMesaDataStoreConfig
