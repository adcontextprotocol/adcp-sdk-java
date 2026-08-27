package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verification utilities for {@code refine_proposals} responses.
 *
 * <p>Checks response ordering, budget and product constraints, unique
 * alternatives, machine-readable failure subsets, and finalize/expiry
 * semantics per the AdCP 3.2 specification.
 *
 * <p>All methods return a list of violations found. An empty list
 * means the response passed verification.
 */
public final class ResponseVerifier {

    private ResponseVerifier() {}

    /**
     * Runs all verification checks on a completed response.
     *
     * @param request  the original request
     * @param response the response to verify
     * @return list of violation descriptions (empty if valid)
     */
    public static List<String> verify(RefineProposalsRequest request,
                                      RefineProposalsResponse response) {
        List<String> violations = new ArrayList<>();

        if (!response.isCompleted() || response.results() == null) {
            return violations;
        }

        verifyResultOrdering(request, response, violations);
        verifyFinalizeHomogeneity(response, violations);
        verifyLineage(request, response, violations);
        verifyUniqueProposalIds(response, violations);
        verifyPartialInvariant(response, violations);
        verifyOutcomeConstraints(response, violations);
        verifyConstraintSatisfaction(request, response, violations);

        return violations;
    }

    /**
     * Verifies that results preserve request ordering: each result's
     * source_proposal_id matches the corresponding refinement entry.
     */
    public static void verifyResultOrdering(RefineProposalsRequest request,
                                            RefineProposalsResponse response,
                                            List<String> violations) {
        List<RefinementResult> results = response.results();
        List<ProposalRefinement> refinements = request.refinements();

        if (results == null) return;

        if (results.size() != refinements.size()) {
            violations.add("result count " + results.size()
                    + " does not match refinement count " + refinements.size());
            return;
        }

        for (int i = 0; i < results.size(); i++) {
            String expectedId = refinements.get(i).proposalId();
            String actualId = results.get(i).sourceProposalId();
            if (!expectedId.equals(actualId)) {
                violations.add("result[" + i + "] source_proposal_id '"
                        + actualId + "' does not match request '"
                        + expectedId + "'");
            }
        }
    }

    /**
     * Verifies that if any result is finalized, all results are finalized
     * (atomic finalize batch invariant).
     */
    public static void verifyFinalizeHomogeneity(RefineProposalsResponse response,
                                                 List<String> violations) {
        List<RefinementResult> results = response.results();
        if (results == null || results.isEmpty()) return;

        boolean anyFinalized = results.stream()
                .anyMatch(r -> r.outcome() == RefinementOutcome.FINALIZED);
        if (!anyFinalized) return;

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).outcome() != RefinementOutcome.FINALIZED) {
                violations.add("result[" + i + "] is " + results.get(i).outcome()
                        + " but batch contains finalized results"
                        + " (finalize batches must be all-or-none)");
            }
        }
    }

    /**
     * Verifies lineage: every returned proposal must carry a
     * parent_proposal_id equal to the entry's source_proposal_id.
     */
    public static void verifyLineage(RefineProposalsRequest request,
                                     RefineProposalsResponse response,
                                     List<String> violations) {
        List<RefinementResult> results = response.results();
        if (results == null) return;

        for (int i = 0; i < results.size(); i++) {
            RefinementResult result = results.get(i);
            JsonNode proposal = extractProposal(result);
            if (proposal == null) continue;

            JsonNode parentId = proposal.get("parent_proposal_id");
            if (parentId == null || parentId.isNull()) {
                violations.add("result[" + i + "] proposal is missing parent_proposal_id");
            } else if (!parentId.asText().equals(result.sourceProposalId())) {
                violations.add("result[" + i + "] parent_proposal_id '"
                        + parentId.asText()
                        + "' does not match source_proposal_id '"
                        + result.sourceProposalId() + "'");
            }
        }
    }

    /**
     * Verifies that all returned proposal_ids are unique (no duplicates
     * across alternatives).
     */
    public static void verifyUniqueProposalIds(RefineProposalsResponse response,
                                               List<String> violations) {
        List<RefinementResult> results = response.results();
        if (results == null) return;

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            JsonNode proposal = extractProposal(results.get(i));
            if (proposal == null) continue;

            JsonNode pid = proposal.get("proposal_id");
            if (pid != null && !pid.isNull()) {
                if (!seen.add(pid.asText())) {
                    violations.add("result[" + i + "] duplicate proposal_id: "
                            + pid.asText());
                }
            }
        }
    }

    /**
     * Verifies the partial invariant: on a partial result, every
     * constraint not listed in unsatisfied_constraints is fully
     * satisfied by the draft.
     *
     * <p>Currently checks that unsatisfied_constraints is present
     * and non-empty for partial outcomes.
     */
    public static void verifyPartialInvariant(RefineProposalsResponse response,
                                              List<String> violations) {
        List<RefinementResult> results = response.results();
        if (results == null) return;

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) instanceof RefinementResult.Partial partial) {
                if (partial.unsatisfiedConstraints() == null
                        || partial.unsatisfiedConstraints().isEmpty()) {
                    violations.add("result[" + i
                            + "] is partial but has no unsatisfied_constraints");
                }
            }
        }
    }

    /**
     * Verifies outcome-specific structural constraints:
     * - revised: must have proposal with status=draft, no reason/notes
     * - partial: must have proposal with status=draft, must have notes
     * - finalized: must have proposal with status=committed and expires_at
     * - unable: must have reason, must not have proposal
     */
    public static void verifyOutcomeConstraints(RefineProposalsResponse response,
                                                List<String> violations) {
        List<RefinementResult> results = response.results();
        if (results == null) return;

        for (int i = 0; i < results.size(); i++) {
            RefinementResult result = results.get(i);
            switch (result) {
                case RefinementResult.Revised r -> {
                    if (r.proposal() == null || r.proposal().isNull()) {
                        violations.add("result[" + i + "] revised but missing proposal");
                    } else {
                        checkProposalStatus(r.proposal(), "draft", i, violations);
                    }
                }
                case RefinementResult.Partial p -> {
                    if (p.proposal() == null || p.proposal().isNull()) {
                        violations.add("result[" + i + "] partial but missing proposal");
                    } else {
                        checkProposalStatus(p.proposal(), "draft", i, violations);
                    }
                    if (p.notes() == null || p.notes().isBlank()) {
                        violations.add("result[" + i + "] partial but missing notes");
                    }
                }
                case RefinementResult.Finalized f -> {
                    if (f.proposal() == null || f.proposal().isNull()) {
                        violations.add("result[" + i + "] finalized but missing proposal");
                    } else {
                        checkProposalStatus(f.proposal(), "committed", i, violations);
                        JsonNode expiresAt = f.proposal().get("expires_at");
                        if (expiresAt == null || expiresAt.isNull()) {
                            violations.add("result[" + i
                                    + "] finalized proposal missing expires_at");
                        }
                    }
                }
                case RefinementResult.Unable u -> {
                    if (u.reason() == null || u.reason().isBlank()) {
                        violations.add("result[" + i + "] unable but missing reason");
                    }
                }
            }
        }
    }

    /**
     * Verifies that all returned proposals have valid terms_digest values
     * matching their commercial_terms.
     */
    public static List<String> verifyDigests(RefineProposalsResponse response) {
        List<String> violations = new ArrayList<>();
        List<RefinementResult> results = response.results();
        if (results == null) return violations;

        for (int i = 0; i < results.size(); i++) {
            JsonNode proposal = extractProposal(results.get(i));
            if (proposal == null) continue;

            JsonNode digestNode = proposal.get("terms_digest");
            JsonNode termsNode = proposal.get("commercial_terms");

            if (digestNode != null && !digestNode.isNull()
                    && termsNode != null && !termsNode.isNull()) {
                if (!TermsDigest.verify(digestNode.asText(), termsNode)) {
                    violations.add("result[" + i + "] terms_digest does not match "
                            + "recomputed SHA-256 of commercial_terms");
                }
            }
        }

        return violations;
    }

    /**
     * Verifies that revised results satisfy the request's typed constraints.
     * Only checks revised outcomes (partial/unable carry their own diagnostics).
     * Verifies budget bounds and CPM ceiling against commercial_terms.
     */
    public static void verifyConstraintSatisfaction(
            RefineProposalsRequest request,
            RefineProposalsResponse response,
            List<String> violations) {
        List<RefinementResult> results = response.results();
        List<ProposalRefinement> refinements = request.refinements();
        if (results == null) return;

        for (int i = 0; i < Math.min(results.size(), refinements.size()); i++) {
            if (!(results.get(i) instanceof RefinementResult.Revised revised)) continue;

            RefinementConstraints constraints = refinements.get(i).constraints();
            if (constraints == null) continue;

            JsonNode proposal = revised.proposal();
            if (proposal == null || proposal.isNull()) continue;

            JsonNode terms = proposal.get("commercial_terms");
            if (terms == null || terms.isNull()) continue;

            if (constraints.totalBudget() != null) {
                verifyBudgetBounds(terms, constraints.totalBudget(), i, violations);
            }
            if (constraints.cpm() != null) {
                verifyCpmCeiling(terms, constraints.cpm(), i, violations);
            }
        }
    }

    private static void verifyBudgetBounds(JsonNode terms, TotalBudgetConstraint budget,
                                           int index, List<String> violations) {
        JsonNode totalBudget = terms.get("total_budget");
        if (totalBudget == null || totalBudget.isNull()) {
            violations.add("result[" + index
                    + "] revised but commercial_terms.total_budget is missing"
                    + " (budget constraint present)");
            return;
        }
        JsonNode amountNode = totalBudget.get("amount");
        JsonNode currencyNode = totalBudget.get("currency");
        if (amountNode == null || currencyNode == null) return;

        String currency = currencyNode.asText();
        if (!budget.currency().equals(currency)) {
            violations.add("result[" + index
                    + "] budget currency mismatch: constraint=" + budget.currency()
                    + " proposal=" + currency);
            return;
        }

        BigDecimal amount = amountNode.decimalValue();
        if (budget.min() != null && amount.compareTo(budget.min()) < 0) {
            violations.add("result[" + index
                    + "] budget " + amount + " below constraint min " + budget.min());
        }
        if (budget.max() != null && amount.compareTo(budget.max()) > 0) {
            violations.add("result[" + index
                    + "] budget " + amount + " above constraint max " + budget.max());
        }
    }

    private static void verifyCpmCeiling(JsonNode terms, CpmConstraint cpm,
                                         int index, List<String> violations) {
        JsonNode purchases = terms.get("purchases");
        if (purchases == null || !purchases.isArray()) return;

        for (int p = 0; p < purchases.size(); p++) {
            JsonNode pricing = purchases.get(p).get("pricing");
            if (pricing == null) continue;

            JsonNode model = pricing.get("pricing_model");
            if (model == null) continue;
            String modelStr = model.asText();
            if (!"cpm".equals(modelStr) && !"vcpm".equals(modelStr)) continue;

            JsonNode currencyNode = pricing.get("currency");
            if (currencyNode != null && !cpm.currency().equals(currencyNode.asText())) continue;

            JsonNode fixedPrice = pricing.get("fixed_price");
            if (fixedPrice != null && fixedPrice.decimalValue().compareTo(cpm.max()) > 0) {
                violations.add("result[" + index + "] purchase[" + p
                        + "] CPM " + fixedPrice.decimalValue()
                        + " exceeds constraint max " + cpm.max());
            }
        }
    }

    private static void checkProposalStatus(JsonNode proposal, String expected,
                                            int index, List<String> violations) {
        JsonNode status = proposal.get("proposal_status");
        if (status == null || status.isNull()) {
            violations.add("result[" + index + "] proposal missing proposal_status");
        } else if (!expected.equals(status.asText())) {
            violations.add("result[" + index + "] expected proposal_status '"
                    + expected + "' but got '" + status.asText() + "'");
        }
    }

    private static JsonNode extractProposal(RefinementResult result) {
        return switch (result) {
            case RefinementResult.Revised r -> r.proposal();
            case RefinementResult.Partial p -> p.proposal();
            case RefinementResult.Finalized f -> f.proposal();
            case RefinementResult.Unable u -> null;
        };
    }
}
