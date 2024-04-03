/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.cdk.integrations.destination.staging

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.base.Preconditions
import io.airbyte.cdk.core.command.option.ConnectorConfiguration
import io.airbyte.cdk.core.command.option.DefaultConnectorConfiguration
import io.airbyte.cdk.core.command.option.DefaultMicronautConfiguredAirbyteCatalog
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer
import io.airbyte.cdk.integrations.destination.async.AsyncStreamConsumer
import io.airbyte.cdk.integrations.destination.async.FlushWorkers
import io.airbyte.cdk.integrations.destination.async.buffers.BufferManager
import io.airbyte.cdk.integrations.destination.async.deser.IdentityDataTransformer
import io.airbyte.cdk.integrations.destination.async.deser.StreamAwareDataTransformer
import io.airbyte.cdk.integrations.destination.async.state.FlushFailure
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnCloseFunction
import io.airbyte.cdk.integrations.destination.jdbc.WriteConfig
import io.airbyte.commons.exceptions.ConfigErrorException
import io.airbyte.integrations.base.destination.typing_deduping.ParsedCatalog
import io.airbyte.integrations.base.destination.typing_deduping.TypeAndDedupeOperationValve
import io.airbyte.integrations.base.destination.typing_deduping.TyperDeduper
import io.airbyte.protocol.models.v0.*
import java.time.Instant
import java.util.*
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Collectors
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Uses both Factory and Consumer design pattern to create a single point of creation for consuming
 * [AirbyteMessage] for processing
 */
class StagingConsumerFactory
private constructor(
    private val outputRecordCollector: Consumer<AirbyteMessage>?,
    private val database: JdbcDatabase?,
    private val stagingOperations: StagingOperations?,
    private val namingResolver: NamingConventionTransformer?,
    private val config: JsonNode?,
    private val catalog: ConfiguredAirbyteCatalog?,
    private val purgeStagingData: Boolean,
    private val typerDeduperValve: TypeAndDedupeOperationValve?,
    private val typerDeduper: TyperDeduper?,
    private val parsedCatalog: ParsedCatalog?,
    private val defaultNamespace: String?,
    private val useDestinationsV2Columns: Boolean,
    // Optional fields
    private val bufferMemoryLimit: Optional<Long>,
    private val optimalBatchSizeBytes: Long,
    private val dataTransformer: StreamAwareDataTransformer
) : SerialStagingConsumerFactory() {

    fun createAsync(): SerializedAirbyteMessageConsumer {
        val typerDeduper = this.typerDeduper!!
        val typerDeduperValve = this.typerDeduperValve!!
        val stagingOperations = this.stagingOperations!!

        val writeConfigs: List<WriteConfig> =
            createWriteConfigs(
                namingResolver,
                config,
                catalog,
                parsedCatalog,
                useDestinationsV2Columns
            )
        val streamDescToWriteConfig: Map<StreamDescriptor, WriteConfig> =
            streamDescToWriteConfig(writeConfigs)
        val flusher =
            AsyncFlush(
                streamDescToWriteConfig,
                stagingOperations,
                database,
                catalog,
                typerDeduperValve,
                typerDeduper,
                optimalBatchSizeBytes,
                useDestinationsV2Columns
            )
        val bufferManager = BufferManager(getMemoryLimit(bufferMemoryLimit))
        val flushWorkers =
            FlushWorkers(
                bufferManager.stateManager,
                bufferManager.bufferDequeue,
                flusher,
                outputRecordCollector!!,
                Executors.newFixedThreadPool(5),
                FlushFailure(),
            )
        val micronautConfiguredAirbyteCatalog = DefaultMicronautConfiguredAirbyteCatalog(catalog!!)
        val configuration: ConnectorConfiguration = DefaultConnectorConfiguration(defaultNamespace)
        return AsyncStreamConsumer(
            GeneralStagingFunctions.onStartFunction(
                database!!,
                stagingOperations,
                writeConfigs,
                typerDeduper
            ), // todo (cgardens) - wrapping the old close function to avoid more code churn.
            OnCloseFunction { _, streamSyncSummaries ->
                try {
                    GeneralStagingFunctions.onCloseFunction(
                            database,
                            stagingOperations,
                            writeConfigs,
                            purgeStagingData,
                            typerDeduper
                        )
                        .accept(false, streamSyncSummaries)
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            },
            configuration,
            micronautConfiguredAirbyteCatalog,
            bufferManager,
            flushWorkers,
            dataTransformer = dataTransformer
        )
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(StagingConsumerFactory::class.java)

        private val SYNC_DATETIME: Instant = Instant.now()

        private fun getMemoryLimit(bufferMemoryLimit: Optional<Long>): Long {
            return bufferMemoryLimit.orElse(
                (Runtime.getRuntime().maxMemory() * BufferManager.MEMORY_LIMIT_RATIO).toLong()
            )
        }

        private fun streamDescToWriteConfig(
            writeConfigs: List<WriteConfig>
        ): Map<StreamDescriptor, WriteConfig> {
            val conflictingStreams: MutableSet<WriteConfig> = HashSet()
            val streamDescToWriteConfig: MutableMap<StreamDescriptor, WriteConfig> =
                HashMap<StreamDescriptor, WriteConfig>()
            for (config in writeConfigs) {
                val streamIdentifier = toStreamDescriptor(config)
                if (streamDescToWriteConfig.containsKey(streamIdentifier)) {
                    conflictingStreams.add(config)
                    val existingConfig: WriteConfig =
                        streamDescToWriteConfig.getValue(streamIdentifier)
                    // The first conflicting stream won't have any problems, so we need to
                    // explicitly add it here.
                    conflictingStreams.add(existingConfig)
                } else {
                    streamDescToWriteConfig[streamIdentifier] = config
                }
            }
            if (!conflictingStreams.isEmpty()) {
                val message =
                    String.format(
                        "You are trying to write multiple streams to the same table. Consider switching to a custom namespace format using \${SOURCE_NAMESPACE}, or moving one of them into a separate connection with a different stream prefix. Affected streams: %s",
                        conflictingStreams
                            .stream()
                            .map<String>(
                                Function<WriteConfig, String> { config: WriteConfig ->
                                    config.namespace + "." + config.streamName
                                }
                            )
                            .collect(Collectors.joining(", "))
                    )
                throw ConfigErrorException(message)
            }
            return streamDescToWriteConfig
        }

        private fun toStreamDescriptor(config: WriteConfig): StreamDescriptor {
            return StreamDescriptor().withName(config.streamName).withNamespace(config.namespace)
        }

        /**
         * Creates a list of all [WriteConfig] for each stream within a [ConfiguredAirbyteCatalog].
         * Each write config represents the configuration settings for writing to a destination
         * connector
         *
         * @param namingResolver [NamingConventionTransformer] used to transform names that are
         * acceptable by each destination connector
         * @param config destination connector configuration parameters
         * @param catalog [ConfiguredAirbyteCatalog] collection of configured
         * [ConfiguredAirbyteStream]
         * @return list of all write configs for each stream in a [ConfiguredAirbyteCatalog]
         */
        private fun createWriteConfigs(
            namingResolver: NamingConventionTransformer?,
            config: JsonNode?,
            catalog: ConfiguredAirbyteCatalog?,
            parsedCatalog: ParsedCatalog?,
            useDestinationsV2Columns: Boolean
        ): List<WriteConfig> {
            return catalog!!
                .streams
                .stream()
                .map(toWriteConfig(namingResolver, config, parsedCatalog, useDestinationsV2Columns))
                .toList()
        }

        private fun toWriteConfig(
            namingResolver: NamingConventionTransformer?,
            config: JsonNode?,
            parsedCatalog: ParsedCatalog?,
            useDestinationsV2Columns: Boolean
        ): Function<ConfiguredAirbyteStream, WriteConfig> {
            return Function<ConfiguredAirbyteStream, WriteConfig> { stream: ConfiguredAirbyteStream
                ->
                Preconditions.checkNotNull(
                    stream.destinationSyncMode,
                    "Undefined destination sync mode"
                )
                val abStream = stream.stream
                val streamName = abStream.name

                val outputSchema: String
                val tableName: String
                if (useDestinationsV2Columns) {
                    val streamId = parsedCatalog!!.getStream(abStream.namespace, streamName).id
                    outputSchema = streamId.rawNamespace!!
                    tableName = streamId.rawName!!
                } else {
                    outputSchema =
                        getOutputSchema(abStream, config!!["schema"].asText(), namingResolver)
                    tableName = namingResolver!!.getRawTableName(streamName)
                }
                val tmpTableName = namingResolver!!.getTmpTableName(streamName)
                val syncMode = stream.destinationSyncMode

                val writeConfig: WriteConfig =
                    WriteConfig(
                        streamName,
                        abStream.namespace,
                        outputSchema,
                        tmpTableName,
                        tableName,
                        syncMode,
                        SYNC_DATETIME
                    )
                LOGGER.info("Write config: {}", writeConfig)
                writeConfig
            }
        }

        private fun getOutputSchema(
            stream: AirbyteStream,
            defaultDestSchema: String,
            namingResolver: NamingConventionTransformer?
        ): String {
            return if (stream.namespace != null) namingResolver!!.getNamespace(stream.namespace)
            else namingResolver!!.getNamespace(defaultDestSchema)
        }
    }
}
