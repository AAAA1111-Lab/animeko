/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.client

import android.os.Build
import android.os.DeadObjectException
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import me.him188.ani.app.domain.torrent.IDisposableHandle
import me.him188.ani.app.domain.torrent.IPieceStateObserver
import me.him188.ani.app.domain.torrent.IRemotePieceList
import me.him188.ani.app.torrent.api.pieces.Piece
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.utils.coroutines.CancellationException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * If [IRemotePieceList][IRemotePieceList] [is dead][DeadObjectException],
 * calling any method or field to this PieceList is undefined behaviour.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
class RemotePieceList(
    connectivityAware: ConnectivityAware,
    private val remote: IRemotePieceList,
) : PieceList(
    remote.immutableSizeArray,
    remote.immutableDataOffsetArray,
    remote.immutableInitialPieceIndex,
),
    ConnectivityAware by connectivityAware {
    private val logger = logger<RemotePieceList>()

    private val pieceStateSharedMem by lazy { remote.pieceStateArrayMemRegion }
    private val pieceStateBuf by lazy { pieceStateSharedMem.mapReadOnly() }

    override val Piece.state: PieceState
        get() {
            return if (isConnected) {
                PIECE_STATE_ENTRIES[pieceStateBuf.get(indexInList).toInt()]
            } else {
                logger.warn { "Remote interface $remote is dead, get state $this returns PieceState.NOT_AVAILABLE" }
                PieceState.NOT_AVAILABLE
            }
        }

    init {
        var transform: ConnectivityAware.StateTransform? = null
        transform = registerState(false) {
            try {
                remote.dispose()
            } catch (_: DeadObjectException) {
            }
            transform?.let(::unregister)
        }
    }

    override suspend fun Piece.awaitFinished() {
        if (state == PieceState.FINISHED) return
        if (!isConnected) throw CancellationException("Remote disconnected")

        var disposableHandle: IDisposableHandle? = null
        var transform: ConnectivityAware.StateTransform? = null
        val readyState = try {
            suspendCancellableCoroutine { cont ->
                logger.info { "Awaiting state remote piece $pieceIndex to ${PieceState.FINISHED}." }
                transform = registerState(false) {
                    cont.resumeWithException(CancellationException("Remote disconnected"))
                }
                // remote 必须保证 register observer 调用后一定可以监听到新的 state
                disposableHandle = try {
                    remote.registerPieceStateObserver(
                        pieceIndex,
                        object : IPieceStateObserver.Stub() {
                            override fun onUpdate() {
                                val newState = state
                                if (newState == PieceState.FINISHED) {
                                    cont.resume(newState)
                                }
                            }
                        },
                    )
                } catch (_: DeadObjectException) {
                    logger.warn { "Remote interface $remote is dead, awaitFinished returns PieceState.NOT_AVAILABLE" }
                    cont.resume(PieceState.NOT_AVAILABLE)

                    return@suspendCancellableCoroutine
                }
                // 注册 listener 之后如果 state 是 ready 了，下面就监听不到 ready state 了
                if (state == PieceState.FINISHED) cont.resume(state)
            }
        } finally {
            logger.info { "Got state of remote piece $pieceIndex: $state." }
            try {
                disposableHandle?.dispose()
            } catch (_: DeadObjectException) {
            }
            transform?.let(::unregister)
        }

        check(state == readyState) { "Remote state of piece $this is changed from $readyState to $state" }
    }
    
    companion object {
        private val PIECE_STATE_ENTRIES: List<PieceState> = PieceState.entries
    }
}