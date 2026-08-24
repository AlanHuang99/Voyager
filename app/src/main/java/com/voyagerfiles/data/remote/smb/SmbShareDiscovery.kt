package com.voyagerfiles.data.remote.smb

import com.hierynomus.smbj.session.Session
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.Locale

internal data class SmbDiscoveredShare(
    val name: String,
    val remark: String?,
)

internal data class RawSmbShare(
    val name: String,
    val type: Int,
    val remark: String?,
)

internal fun diskShares(shares: List<RawSmbShare>): List<SmbDiscoveredShare> = shares
    .asSequence()
    .filter { (it.type and 0xFFFF) == 0 }
    .mapNotNull { share ->
        val name = share.name.trim()
        name.takeIf(String::isNotEmpty)?.let {
            SmbDiscoveredShare(
                name = it,
                remark = share.remark?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }
    .distinctBy { it.name.lowercase(Locale.ROOT) }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    .toList()

internal fun interface SmbShareDiscovery {
    fun discover(session: Session): List<SmbDiscoveredShare>
}

internal object DceRpcSmbShareDiscovery : SmbShareDiscovery {
    override fun discover(session: Session): List<SmbDiscoveredShare> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).shares1
            .map { RawSmbShare(it.netName, it.type, it.remark) }
            .let(::diskShares)
    }
}
