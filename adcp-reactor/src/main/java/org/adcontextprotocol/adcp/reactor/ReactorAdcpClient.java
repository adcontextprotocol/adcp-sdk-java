package org.adcontextprotocol.adcp.reactor;

import org.adcontextprotocol.adcp.AdcpClient;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

/** Non-blocking Project Reactor bridge for the synchronous AdCP client. */
public final class ReactorAdcpClient {

    private final AdcpClient delegate;

    public ReactorAdcpClient(AdcpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * Refines proposals on Reactor's bounded-elastic scheduler.
     * Transport and task-level errors are emitted through {@link Mono#error};
     * per-proposal outcomes remain safely discriminated in the response.
     */
    public Mono<RefineProposalsResponse> refineProposals(RefineProposalsRequest request) {
        return Mono.fromCallable(() -> delegate.refineProposals(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
