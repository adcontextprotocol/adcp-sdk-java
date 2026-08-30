// Kotlin extensions on top of the Java AdCP surface. Coroutine suspend-fun
// wrappers and DSL builders land here as the Java public surface stabilizes.
// Nullability is correct because the Java surface is JSpecify-annotated.

package org.adcontextprotocol.adcp.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adcontextprotocol.adcp.AdcpClient
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse

/** Coroutine bridge preserving task errors and sealed per-proposal outcomes. */
public suspend fun AdcpClient.refineProposalsAwait(
    request: RefineProposalsRequest,
): RefineProposalsResponse = withContext(Dispatchers.IO) {
    refineProposals(request)
}
