package me.him188.ani.datasources.dmhy

import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.DownloadSearchQuery
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.TopicMediaSource
import me.him188.ani.datasources.api.topic.Topic
import me.him188.ani.datasources.dmhy.impl.DmhyPagedSourceImpl
import me.him188.ani.datasources.dmhy.impl.protocol.Network
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.error
import kotlin.coroutines.cancellation.CancellationException

class DmhyMediaSource(
    private val client: ScopedHttpClient,
) : TopicMediaSource() {
    class Factory : MediaSourceFactory {
        override val factoryId: FactoryId = FactoryId(ID)
        override val info: MediaSourceInfo get() = INFO
        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient
        ): MediaSource = DmhyMediaSource(client)
    }

    companion object {
        const val ID = "dmhy"
        val INFO = MediaSourceInfo(
            displayName = "動漫花園",
            description = "动漫资源聚合网站",
            iconUrl = "https://dmhy.org/favicon.ico",
            iconResourceId = "dmhy.png",
        )
    }

    override val info: MediaSourceInfo get() = INFO
    private val network by lazy {
        Network(client)
    }

    override val mediaSourceId: String get() = ID

    override suspend fun checkConnection(): ConnectionStatus {
        return try {
            network.list()
            ConnectionStatus.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to check connection" }
            ConnectionStatus.FAILED
        }
    }

    override suspend fun startSearch(query: DownloadSearchQuery): SizedSource<Topic> {
        return DmhyPagedSourceImpl(query, network)
    }
}