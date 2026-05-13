/**
 * Project Reactor bridge — wraps the synchronous {@code adcp} surface in
 * {@code Mono.fromCallable(...)} on a bounded elastic scheduler. Ships at
 * GA (not fast-follow) so WebFlux shops don't wrap the sync API
 * themselves and own that complexity forever.
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.reactor;
