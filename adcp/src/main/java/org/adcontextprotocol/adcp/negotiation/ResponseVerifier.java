package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed verification for {@code refine_proposals} responses. */
public final class ResponseVerifier {

    private ResponseVerifier() {}

    /** Verifies a response using the current time for committed-hold expiry. */
    public static List<String> verify(RefineProposalsRequest request,
                                      RefineProposalsResponse response) {
        return verify(request, response, Instant.now());
    }

    /** Verifies ordering, shapes, lineage, digests, constraints, and expiry. */
    public static List<String> verify(RefineProposalsRequest request,
                                      RefineProposalsResponse response,
                                      Instant now) {
        List<String> violations = new ArrayList<>();
        if (response.status() != null
                && !"completed".equals(response.status())
                && !"submitted".equals(response.status())) {
            violations.add("status must be completed or submitted");
        }
        if (response.isAsync()) {
            if (response.taskId() == null || response.taskId().isBlank()) {
                violations.add("submitted response is missing task_id");
            }
            if (response.results() != null && !response.results().isEmpty()) {
                violations.add("submitted response must not contain results");
            }
            return violations;
        }
        if (response.taskId() != null) {
            violations.add("non-submitted response must not contain task_id");
        }
        if (!response.isCompleted() || response.results() == null) {
            violations.add("completed response is missing results");
            return violations;
        }

        List<RefinementResult> results = response.results();
        List<ProposalRefinement> refinements = request.refinements();
        if (results.size() != refinements.size()) {
            violations.add("result count " + results.size()
                    + " does not match refinement count " + refinements.size());
        }

        Set<String> proposalIds = new HashSet<>();
        boolean finalizeBatch = refinements.stream()
                .allMatch(r -> effectiveAction(r) == RefinementAction.FINALIZE);
        int finalizedResults = 0;
        for (int i = 0; i < Math.min(results.size(), refinements.size()); i++) {
            RefinementResult result = results.get(i);
            ProposalRefinement refinement = refinements.get(i);
            String prefix = "result[" + i + "] ";

            if (!refinement.proposalId().equals(result.sourceProposalId())) {
                violations.add(prefix + "source_proposal_id does not preserve request order");
            }
            if (result.outcome() == RefinementOutcome.FINALIZED) finalizedResults++;
            if ((!finalizeBatch && result.outcome() == RefinementOutcome.FINALIZED)
                    || (finalizeBatch && result.outcome() != RefinementOutcome.FINALIZED
                    && result.outcome() != RefinementOutcome.UNABLE)) {
                violations.add(prefix + "outcome does not match request action");
            }

            List<JsonNode> proposals = proposals(result);
            verifyOutcomeShape(refinement, result, proposals, now, prefix, violations);
            verifyFailureSubsets(refinement, result, prefix, violations);

            Set<String> termsDigests = new HashSet<>();
            for (int p = 0; p < proposals.size(); p++) {
                JsonNode proposal = proposals.get(p);
                String proposalPrefix = prefix + "proposal[" + p + "] ";
                verifyProposal(proposal, result.sourceProposalId(), proposalPrefix,
                        proposalIds, termsDigests, violations);
                verifySatisfiedDimensions(refinement, result, proposal,
                        proposalPrefix, violations);
            }
        }
        if (finalizeBatch && finalizedResults > 0 && finalizedResults != results.size()) {
            violations.add("finalize batch mixes committed and failed results; holds are not atomic");
        }
        return violations;
    }

    private static void verifyOutcomeShape(ProposalRefinement refinement,
                                           RefinementResult result,
                                           List<JsonNode> proposals,
                                           Instant now,
                                           String prefix,
                                           List<String> violations) {
        int expected = refinement.alternatives() == null
                ? 1 : refinement.alternatives().count();
        switch (result) {
            case RefinementResult.Revised ignored -> {
                if (proposals.size() != expected) {
                    violations.add(prefix + "revised returned " + proposals.size()
                            + " proposals, expected " + expected);
                }
                proposals.forEach(p -> checkStatus(p, "draft", prefix, violations));
            }
            case RefinementResult.Partial partial -> {
                if (proposals.isEmpty()) {
                    violations.add(prefix + "partial is missing proposals");
                }
                if (proposals.size() > expected) {
                    violations.add(prefix + "partial returned more proposals than requested");
                }
                if (partial.reasonCode() == null || partial.reason() == null
                        || partial.reason().isBlank()) {
                    violations.add(prefix + "partial is missing reason_code or reason");
                }
                proposals.forEach(p -> checkStatus(p, "draft", prefix, violations));
            }
            case RefinementResult.Finalized finalized -> {
                if (proposals.size() != 1) {
                    violations.add(prefix + "finalized is missing proposal");
                    return;
                }
                JsonNode proposal = finalized.proposal();
                checkStatus(proposal, "committed", prefix, violations);
                JsonNode expires = proposal.get("expires_at");
                if (expires == null || !expires.isTextual()) {
                    violations.add(prefix + "committed proposal is missing expires_at");
                } else {
                    try {
                        if (!OffsetDateTime.parse(expires.textValue()).toInstant().isAfter(now)) {
                            violations.add(prefix + "committed proposal hold is expired");
                        }
                    } catch (DateTimeParseException e) {
                        violations.add(prefix + "committed proposal has invalid expires_at");
                    }
                }
            }
            case RefinementResult.Unable unable -> {
                if (!proposals.isEmpty()) {
                    violations.add(prefix + "unable must not contain proposals");
                }
                if (unable.reasonCode() == null || unable.reason() == null
                        || unable.reason().isBlank()) {
                    violations.add(prefix + "unable is missing reason_code or reason");
                }
            }
        }
    }

    private static void verifyProposal(JsonNode proposal,
                                       String sourceProposalId,
                                       String prefix,
                                       Set<String> allProposalIds,
                                       Set<String> resultDigests,
                                       List<String> violations) {
        if (proposal == null || !proposal.isObject()) {
            violations.add(prefix + "must be an object");
            return;
        }
        String proposalId = text(proposal, "proposal_id");
        if (proposalId == null || proposalId.isBlank()) {
            violations.add(prefix + "is missing proposal_id");
        } else {
            if (proposalId.equals(sourceProposalId)) {
                violations.add(prefix + "must use a fresh proposal_id");
            }
            if (!allProposalIds.add(proposalId)) {
                violations.add(prefix + "duplicates proposal_id " + proposalId);
            }
        }
        if (!Objects.equals(sourceProposalId, text(proposal, "parent_proposal_id"))) {
            violations.add(prefix + "parent_proposal_id does not match source_proposal_id");
        }
        JsonNode terms = proposal.get("commercial_terms");
        String digest = text(proposal, "terms_digest");
        if (terms == null || !terms.isObject() || digest == null
                || !TermsDigest.verify(digest, terms)) {
            violations.add(prefix + "terms_digest does not match commercial_terms");
        } else if (!resultDigests.add(digest)) {
            violations.add(prefix + "duplicates commercial_terms within alternatives");
        }
    }

    private static void verifyFailureSubsets(ProposalRefinement refinement,
                                             RefinementResult result,
                                             String prefix,
                                             List<String> violations) {
        List<String> unsatisfied = unsatisfiedConstraints(result);
        Map<String, String> unsatisfiedProducts = unsatisfiedProductChanges(result);
        Set<String> requested = requestedConstraints(refinement);
        Set<String> reported = new HashSet<>();
        for (String constraint : unsatisfied) {
            if (constraint == null || !reported.add(constraint)) {
                violations.add(prefix + "reports duplicate or empty unsatisfied constraint");
                continue;
            }
            if (!requested.contains(constraint)) {
                violations.add(prefix + "reports unrequested constraint " + constraint);
            }
        }
        Map<String, String> requestedProducts = refinement.productChanges() == null
                ? Map.of() : refinement.productChanges();
        unsatisfiedProducts.forEach((id, action) -> {
            if (!action.equals(requestedProducts.get(id))) {
                violations.add(prefix + "reports unrequested product change " + id);
            }
        });
        ProposalRefinementReason reason = reasonCode(result);
        if ((!unsatisfied.isEmpty() || !unsatisfiedProducts.isEmpty())
                && reason != ProposalRefinementReason.CONSTRAINT_UNSATISFIABLE) {
            violations.add(prefix + "must use constraint_unsatisfiable precedence");
        }
        if (reason == ProposalRefinementReason.CONSTRAINT_UNSATISFIABLE
                && unsatisfied.isEmpty() && unsatisfiedProducts.isEmpty()) {
            violations.add(prefix + "constraint_unsatisfiable has no failure subset");
        }
    }

    private static void verifySatisfiedDimensions(ProposalRefinement refinement,
                                                  RefinementResult result,
                                                  JsonNode proposal,
                                                  String prefix,
                                                  List<String> violations) {
        JsonNode terms = proposal.get("commercial_terms");
        if (terms == null || !terms.isObject()) return;
        Set<String> unsatisfied = new HashSet<>(unsatisfiedConstraints(result));
        Map<String, String> unsatisfiedProducts = unsatisfiedProductChanges(result);
        RefinementConstraints constraints = refinement.constraints();
        if (constraints != null) {
            if (constraints.totalBudget() != null && !unsatisfied.contains("total_budget")) {
                verifyBudget(terms, constraints.totalBudget(), prefix, violations);
            }
            if (constraints.cpm() != null && !unsatisfied.contains("cpm")) {
                verifyCpm(terms, constraints.cpm(), prefix, violations);
            }
            if (constraints.impressions() != null && !unsatisfied.contains("impressions")) {
                verifyImpressions(terms, constraints.impressions(), prefix, violations);
            }
            if (constraints.flight() != null && !unsatisfied.contains("flight")) {
                verifyFlight(terms, constraints.flight(), prefix, violations);
            }
        }
        if (refinement.productChanges() != null) {
            Set<String> present = new HashSet<>();
            JsonNode purchases = terms.get("purchases");
            if (purchases == null || !purchases.isArray() || purchases.isEmpty()) {
                violations.add(prefix + "product changes cannot be verified without purchases");
                return;
            }
            purchases.forEach(p -> {
                String id = text(p, "product_id");
                if (id != null) present.add(id);
            });
            refinement.productChanges().forEach((id, action) -> {
                if (unsatisfiedProducts.containsKey(id)) return;
                if (("include".equals(action) && !present.contains(id))
                        || ("omit".equals(action) && present.contains(id))) {
                    violations.add(prefix + "product change is not satisfied for " + id);
                }
            });
        }
    }

    private static void verifyBudget(JsonNode terms, TotalBudgetConstraint constraint,
                                     String prefix, List<String> violations) {
        JsonNode budget = terms.get("total_budget");
        if (budget == null || !budget.isObject() || !budget.path("amount").isNumber()
                || !budget.path("currency").isTextual()) {
            violations.add(prefix + "total_budget constraint cannot be verified");
            return;
        }
        BigDecimal amount = budget.get("amount").decimalValue();
        if (amount.signum() < 0
                || !constraint.currency().equals(budget.get("currency").textValue())
                || (constraint.min() != null && amount.compareTo(constraint.min()) < 0)
                || (constraint.max() != null && amount.compareTo(constraint.max()) > 0)) {
            violations.add(prefix + "total_budget constraint is not satisfied");
        }
    }

    private static void verifyCpm(JsonNode terms, CpmConstraint constraint,
                                  String prefix, List<String> violations) {
        JsonNode purchases = terms.get("purchases");
        if (purchases == null || !purchases.isArray() || purchases.isEmpty()) {
            violations.add(prefix + "cpm constraint cannot be verified");
            return;
        }
        for (JsonNode purchase : purchases) {
            JsonNode pricing = purchase.get("pricing");
            String model = pricing == null ? null : text(pricing, "pricing_model");
            String currency = pricing == null ? null : text(pricing, "currency");
            JsonNode price = pricing == null ? null : pricing.get("fixed_price");
            if (!("cpm".equals(model) || "vcpm".equals(model))
                    || !constraint.currency().equals(currency) || price == null || !price.isNumber()
                    || price.decimalValue().signum() < 0
                    || price.decimalValue().compareTo(constraint.max()) > 0) {
                violations.add(prefix + "cpm constraint is not satisfied");
                return;
            }
        }
    }

    private static void verifyImpressions(JsonNode terms, ImpressionsConstraint constraint,
                                          String prefix, List<String> violations) {
        JsonNode purchases = terms.get("purchases");
        if (purchases == null || !purchases.isArray() || purchases.isEmpty()) {
            violations.add(prefix + "impressions constraint cannot be verified");
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode purchase : purchases) {
            JsonNode impressions = purchase.get("impressions");
            if (impressions == null || !impressions.isNumber()) {
                violations.add(prefix + "impressions constraint cannot be verified");
                return;
            }
            total = total.add(impressions.decimalValue());
        }
        if (total.compareTo(constraint.min()) < 0) {
            violations.add(prefix + "impressions constraint is not satisfied");
        }
    }

    private static void verifyFlight(JsonNode terms, FlightConstraint constraint,
                                     String prefix, List<String> violations) {
        try {
            if (constraint.startNoLaterThan() != null) {
                String start = text(terms, "start_time");
                if (start == null || "asap".equals(start)
                        || OffsetDateTime.parse(start).isAfter(constraint.startNoLaterThan())) {
                    violations.add(prefix + "flight start constraint is not satisfied");
                }
            }
            if (constraint.endNoEarlierThan() != null) {
                String end = text(terms, "end_time");
                if (end == null
                        || OffsetDateTime.parse(end).isBefore(constraint.endNoEarlierThan())) {
                    violations.add(prefix + "flight end constraint is not satisfied");
                }
            }
        } catch (DateTimeParseException e) {
            violations.add(prefix + "flight constraint cannot be verified");
        }
    }

    private static Set<String> requestedConstraints(ProposalRefinement refinement) {
        Set<String> requested = new HashSet<>();
        RefinementConstraints c = refinement.constraints();
        if (c == null) return requested;
        if (c.totalBudget() != null) requested.add("total_budget");
        if (c.cpm() != null) requested.add("cpm");
        if (c.impressions() != null) requested.add("impressions");
        if (c.flight() != null) requested.add("flight");
        return requested;
    }

    private static List<JsonNode> proposals(RefinementResult result) {
        return switch (result) {
            case RefinementResult.Revised revised -> revised.proposals();
            case RefinementResult.Partial partial -> partial.proposals();
            case RefinementResult.Finalized finalized -> finalized.proposal() == null
                    ? List.of() : List.of(finalized.proposal());
            case RefinementResult.Unable ignored -> List.of();
        };
    }

    private static List<String> unsatisfiedConstraints(RefinementResult result) {
        List<String> value = switch (result) {
            case RefinementResult.Partial partial -> partial.unsatisfiedConstraints();
            case RefinementResult.Unable unable -> unable.unsatisfiedConstraints();
            default -> null;
        };
        return value == null ? List.of() : value;
    }

    private static Map<String, String> unsatisfiedProductChanges(RefinementResult result) {
        Map<String, String> value = switch (result) {
            case RefinementResult.Partial partial -> partial.unsatisfiedProductChanges();
            case RefinementResult.Unable unable -> unable.unsatisfiedProductChanges();
            default -> null;
        };
        return value == null ? Map.of() : value;
    }

    private static ProposalRefinementReason reasonCode(RefinementResult result) {
        return switch (result) {
            case RefinementResult.Partial partial -> partial.reasonCode();
            case RefinementResult.Unable unable -> unable.reasonCode();
            default -> null;
        };
    }

    private static RefinementAction effectiveAction(ProposalRefinement refinement) {
        return refinement.action() == null ? RefinementAction.REVISE : refinement.action();
    }

    private static void checkStatus(JsonNode proposal, String expected,
                                    String prefix, List<String> violations) {
        if (!expected.equals(text(proposal, "proposal_status"))) {
            violations.add(prefix + "proposal_status must be " + expected);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
