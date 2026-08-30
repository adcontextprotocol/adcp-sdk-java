package org.adcontextprotocol.adcp.mutiny;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.adcontextprotocol.adcp.AdcpClient;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;

import java.util.Objects;

/** Non-blocking SmallRye Mutiny bridge for the synchronous AdCP client. */
public final class MutinyAdcpClient {

    private final AdcpClient delegate;

    public MutinyAdcpClient(AdcpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * Refines proposals on Mutiny's default worker pool. Task-level failures
     * fail the {@link Uni}; per-proposal outcomes remain in the typed response.
     */
    public Uni<RefineProposalsResponse> refineProposals(RefineProposalsRequest request) {
        return Uni.createFrom().item(() -> delegate.refineProposals(request))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
