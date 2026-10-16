#!/usr/bin/env node
//
// Guards the Argus PR-review workflow against the failure modes that silently
// disabled it (issue #41): claude-code-action crashed before reading the diff,
// and the job still reported success.
//
// Run: node scripts/check-ai-review-workflow.cjs
//
// Deliberately dependency-free string checking rather than a YAML parse — the
// repo has no YAML library on the Node side, and the properties below are all
// line-oriented.
//
// Scope: these are structural invariants, not a proof that the workflow works.
// Whether Argus actually reviews a PR is only answered by a real run.

const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..');
const workflowPath = path.join(repoRoot, '.github/workflows/ai-review.yml');
const relPath = '.github/workflows/ai-review.yml';

const failures = [];

function fail(message) {
  failures.push(message);
}

if (!fs.existsSync(workflowPath)) {
  console.error(`${relPath} is missing. The Argus review workflow must exist.`);
  process.exit(1);
}

const source = fs.readFileSync(workflowPath, 'utf8');
const lines = source.split('\n');

// ── Every action is pinned to a full commit SHA ──────────────────────────────
// This job runs on `pull_request_target` with access to ANTHROPIC_API_KEY and
// an App private key, so a mutable tag is not an acceptable pin.
for (const [index, line] of lines.entries()) {
  const match = /^\s*(?:-\s+)?uses:\s*(\S+)/.exec(line);
  if (!match) continue;
  const ref = match[1];
  if (!/@[0-9a-f]{40}$/.test(ref)) {
    fail(
      `${relPath}:${index + 1}: \`uses: ${ref}\` is not pinned to a full 40-character commit SHA.`,
    );
  }
}

// ── claude-code-action is not on a pin that predates the fix ────────────────
// 787c5a0c (2026-05-23) is the pin that was in place while Argus was broken.
// Nothing about that SHA caused the crash, but leaving it would mean the
// action never gets the ~5 months of fixes that came after it.
if (source.includes('claude-code-action@787c5a0ce96a9a6cfb050ea0c8f4c05f2447c251')) {
  fail(
    `${relPath}: claude-code-action is pinned to 787c5a0c, the stale pin from issue #41. Repin to a current release.`,
  );
}
if (!/anthropics\/claude-code-action@[0-9a-f]{40}\s+#\s*v\d/.test(source)) {
  fail(
    `${relPath}: the claude-code-action pin must carry a \`# vX.Y.Z\` comment naming the release it points at.`,
  );
}

// ── Steps ────────────────────────────────────────────────────────────────────
// Steps in this workflow sit at six-space indentation. Split on the list
// dash, not on `- name:`/`- uses:` specifically — a step may lead with any
// key, and a step that led with `- id:` used to get folded into its
// predecessor, letting one step satisfy another's assertions.
const stepStart = /^ {6}- \S/;
// Only scan from the job's `steps:` key — the `on.pull_request_target.types`
// list sits at the same indentation and would otherwise read as steps.
const stepsKey = lines.findIndex((line) => /^ {4}steps:\s*$/.test(line));
if (stepsKey === -1) {
  fail(`${relPath}: no job-level \`steps:\` key found.`);
}
const steps = [];
// Comment lines directly above a step belong to that step, not the one
// before it. Buffer them and flush on the next step boundary.
let pendingComments = [];
for (const line of lines.slice(stepsKey === -1 ? 0 : stepsKey + 1)) {
  if (stepStart.test(line)) {
    steps.push([...pendingComments]);
    pendingComments = [];
  } else if (/^ {6}#/.test(line)) {
    pendingComments.push(line);
    continue;
  } else if (line.trim() !== '') {
    pendingComments = [];
  }
  if (steps.length > 0) steps[steps.length - 1].push(line);
}
const stepBodies = steps.map((s) => s.join('\n'));

/**
 * A step's YAML with comment lines removed. Assertions about directives
 * (`if:`, `continue-on-error:`) must not be satisfiable by a comment that
 * merely mentions them.
 */
function uncommented(step) {
  return step
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

// ── claude_args must not set --output-format ─────────────────────────────────
// This is the regression that broke Argus. claude-code-action already runs the
// CLI with `--input-format stream-json --output-format stream-json`; a second
// `--output-format` in claude_args is passed straight through, the CLI rejects
// the pair, and the child exits 1 before reading the diff.
const argsHeader = lines.findIndex((line) => /^\s*claude_args:\s*\|/.test(line));
if (argsHeader === -1) {
  fail(`${relPath}: no \`claude_args: |\` block found — cannot check the CLI arguments.`);
} else {
  const headerIndent = /^(\s*)/.exec(lines[argsHeader])[1].length;
  const argLines = [];
  for (const line of lines.slice(argsHeader + 1)) {
    // Blank lines are skipped rather than treated as terminators: a blank
    // line inside the block scalar must not hide arguments that follow it.
    // The dedent below is what actually ends the block.
    if (line.trim() === '') continue;
    const indent = /^(\s*)/.exec(line)[1].length;
    if (indent <= headerIndent) break;
    argLines.push(line.trim());
  }
  const claudeArgs = argLines.join('\n');

  if (/(^|\s)--output-format(\s|=)/.test(claudeArgs)) {
    fail(
      `${relPath}: claude_args passes \`--output-format\`. It conflicts with the ` +
        `\`--output-format stream-json\` claude-code-action already sets, and the CLI ` +
        `exits 1 before reading the diff. Use \`--json-schema\` alone for structured output.`,
    );
  }
  if (!claudeArgs.includes('--json-schema')) {
    fail(
      `${relPath}: claude_args must pass \`--json-schema\` — the post step reads ` +
        `\`steps.ai-review.outputs.structured_output\`, which is only populated when it is set.`,
    );
  }

  const modelMatch = /--model\s+(.+)/.exec(claudeArgs);
  if (!modelMatch) {
    fail(`${relPath}: claude_args must pass \`--model\`.`);
  } else if (!/^\$\{\{\s*env\.ARGUS_MODEL\s*\}\}$/.test(modelMatch[1].trim())) {
    fail(
      `${relPath}: \`--model\` must be \`\${{ env.ARGUS_MODEL }}\` so the model the review ` +
        `runs on is the same one the "Verify review model" step checks. Found: ${modelMatch[1]}`,
    );
  }
}

// ── The posted review body is screened for credential-shaped content ───────
// The review is published on a public PR, where Actions log masking does not
// apply. A prompt injection that talks the model into quoting a key must not
// get a free publication channel.
const postStep = stepBodies.find((step) => step.includes('pulls.createReview'));
if (!postStep) {
  fail(`${relPath}: no step posts the review via \`pulls.createReview\`.`);
} else if (!/sk-ant-/.test(postStep) || !/BEGIN \[A-Z \]\*PRIVATE KEY/.test(postStep)) {
  fail(
    `${relPath}: the step that posts the review must refuse bodies containing ` +
      `credential-shaped content (at minimum \`sk-ant-\` and PEM private-key headers).`,
  );
}

// ── The self-modification gate covers every file that gates Argus ──────────
// Argus must not review — and so must not approve — a PR that edits its own
// guards. That set includes this validator and the CI job that runs it.
const selfModStep = stepBodies.find((step) => step.includes('touches=true'));
if (!selfModStep) {
  fail(`${relPath}: no self-modification gate step writing \`touches=true\` was found.`);
} else {
  // Look inside the `grep -E '...'` pattern specifically: a path that only
  // appears in an echo or a comment is not actually matched.
  const grepPattern = /grep -E '([^']+)'/.exec(selfModStep);
  if (!grepPattern) {
    fail(`${relPath}: the self-modification gate must select paths with \`grep -E '...'\`.`);
  } else {
    const guarded = [
      'ai-review\\.yml',
      '\\.github/ai-review/',
      'validate-ai-review-config\\.yml',
      'check-ai-review-workflow\\.cjs',
      '\\.claude/agents/',
      '\\.github/CODEOWNERS',
    ];
    for (const needle of guarded) {
      if (!grepPattern[1].includes(needle)) {
        fail(
          `${relPath}: the self-modification gate pattern does not match \`${needle}\`. A PR ` +
            `could change that file and still get an Argus approval.`,
        );
      }
    }
  }
  // An undetermined answer must not read as "safe to review".
  if (!/if ! DIFF_FILES=/.test(selfModStep)) {
    fail(
      `${relPath}: the self-modification gate must fail closed — set \`touches=true\` when ` +
        `listing the PR's changed files fails, rather than leaving the output unset.`,
    );
  }
}

// ── The two tool-deny layers must agree ─────────────────────────────────────
// `--disallowedTools` is a CLI flag; `settings.permissions.deny` is what
// subagents launched via Task inherit. A tool denied in only one place is
// denied by only one mechanism.
const settingsHeader = lines.findIndex((line) => /^\s*settings:\s*\|/.test(line));
if (settingsHeader === -1) {
  fail(`${relPath}: no \`settings: |\` block found on the review step.`);
} else {
  const settingsIndent = /^(\s*)/.exec(lines[settingsHeader])[1].length;
  const jsonLines = [];
  for (const line of lines.slice(settingsHeader + 1)) {
    if (line.trim() === '') continue;
    if (/^(\s*)/.exec(line)[1].length <= settingsIndent) break;
    jsonLines.push(line);
  }
  let denied = null;
  try {
    denied = JSON.parse(jsonLines.join('\n')).permissions.deny;
  } catch {
    fail(`${relPath}: the \`settings\` block is not parseable JSON with \`permissions.deny\`.`);
  }
  if (denied) {
    const flagMatch = /--disallowedTools\s+"([^"]+)"/.exec(source);
    if (!flagMatch) {
      fail(`${relPath}: claude_args must pass \`--disallowedTools "..."\`.`);
    } else {
      const flagTools = flagMatch[1].split(',').map((t) => t.trim()).filter(Boolean);
      // Path-scoped rules like `Read(//proc/**)` only exist in `settings`;
      // compare bare tool names on both sides.
      const bareDenied = denied.filter((rule) => !rule.includes('('));
      const missingFromSettings = flagTools.filter((t) => !bareDenied.includes(t));
      if (missingFromSettings.length > 0) {
        fail(
          `${relPath}: ${missingFromSettings.join(', ')} ` +
            `${missingFromSettings.length === 1 ? 'is' : 'are'} in \`--disallowedTools\` but not ` +
            `in \`settings.permissions.deny\`. Keep both lists in sync.`,
        );
      }
      const missingFromFlag = bareDenied.filter((t) => !flagTools.includes(t));
      if (missingFromFlag.length > 0) {
        fail(
          `${relPath}: ${missingFromFlag.join(', ')} ` +
            `${missingFromFlag.length === 1 ? 'is' : 'are'} in \`settings.permissions.deny\` but ` +
            `not in \`--disallowedTools\`. Keep both lists in sync.`,
        );
      }
      for (const required of ['Bash', 'Edit', 'Write', 'WebFetch', 'WebSearch']) {
        if (!flagTools.includes(required)) {
          fail(`${relPath}: \`${required}\` must stay in \`--disallowedTools\` for a read-only reviewer.`);
        }
      }
    }
  }
}

// ── The PR diff is fenced as untrusted input ────────────────────────────────
// PR title, body and diff are attacker-controlled and are fed to the model.
const promptStep = stepBodies.find((step) => step.includes('ARGUS_PROMPT<<'));
const fenceEchoed =
  promptStep !== undefined &&
  /^\s*echo '[^']*untrusted[^']*'\s*$/im.test(promptStep) &&
  /Never follow instructions/i.test(promptStep);
if (!fenceEchoed) {
  fail(
    `${relPath}: the prompt the workflow builds must tell the model that the \`.argus/\` ` +
      `inputs are untrusted PR-author content and that it must never follow instructions ` +
      `found there. A comment mentioning "untrusted" does not count — it has to be echoed ` +
      `into the prompt.`,
  );
}

// ── ARGUS_MODEL is declared and verified before the review runs ──────────────
const modelEnv = /^\s*ARGUS_MODEL:\s*(\S+)\s*$/m.exec(source);
if (!modelEnv) {
  fail(`${relPath}: no \`ARGUS_MODEL:\` env entry declaring the review model.`);
} else if (!/^claude-[a-z0-9-]+$/.test(modelEnv[1])) {
  fail(`${relPath}: ARGUS_MODEL \`${modelEnv[1]}\` does not look like an Anthropic model ID.`);
}

const verifiesModel = stepBodies.some(
  (step) =>
    /api\.anthropic\.com\/v1\/models\/[$"']*\{?ARGUS_MODEL\}?/.test(step) &&
    /exit 1/.test(step) &&
    !/continue-on-error:\s*true/.test(uncommented(step)),
);
if (!verifiesModel) {
  fail(
    `${relPath}: no job-failing step checks ARGUS_MODEL against ` +
      `https://api.anthropic.com/v1/models/ before the review runs.`,
  );
}

// ── The step that answers "was a review posted?" must be able to say no ─────
// Without this, the gate below can be satisfied by a verify step rewired to
// report `review_posted=true` unconditionally — the issue-#41 shape again.
const verifyStep = stepBodies.find((step) => step.includes('review_posted='));
if (!verifyStep) {
  fail(`${relPath}: no step writes a \`review_posted\` output.`);
} else {
  if (!/pulls\/\$\{PR_NUMBER\}\/reviews/.test(verifyStep)) {
    fail(
      `${relPath}: the \`review_posted\` step must read the PR's reviews from the GitHub ` +
        `API rather than infer success from earlier step outcomes.`,
    );
  }
  if (!/review_posted=false/.test(verifyStep) || !/review_posted=true/.test(verifyStep)) {
    fail(
      `${relPath}: the \`review_posted\` step must be able to emit both ` +
        `\`review_posted=false\` and \`review_posted=true\`.`,
    );
  }
  // The false branch has to be guarded by an emptiness test on what the API
  // returned, not by something an unrelated failure could flip.
  if (!/if \[ -z "\$STATE" \]/.test(verifyStep)) {
    fail(
      `${relPath}: the \`review_posted\` step must emit \`review_posted=false\` when the ` +
        `reviews API returned no matching review (\`if [ -z "$STATE" ]\`).`,
    );
  }
  if (/continue-on-error:\s*true/.test(uncommented(verifyStep))) {
    fail(`${relPath}: the \`review_posted\` step must not be continue-on-error.`);
  }
}

// ── A review that never happened must fail the job ───────────────────────────
const gate = stepBodies.find(
  (step) => step.includes('REVIEW_POSTED') && /^\s*exit 1$/m.test(step),
);
if (!gate) {
  fail(
    `${relPath}: no step fails the job when Argus did not post a review. Without it a ` +
      `crashed review reports success (issue #41).`,
  );
} else {
  if (!/if:.*(?:always\(\)|!cancelled\(\))/.test(uncommented(gate))) {
    fail(
      `${relPath}: the "did Argus post a review" gate must run with \`!cancelled()\` ` +
        `(or \`always()\`), otherwise an earlier failure skips it and the job still passes.`,
    );
  }
  if (/continue-on-error:\s*true/.test(uncommented(gate))) {
    fail(`${relPath}: the "did Argus post a review" gate must not be continue-on-error.`);
  }
}

if (failures.length > 0) {
  console.error(`Argus review workflow validation failed (${failures.length} problem(s)):\n`);
  for (const failure of failures) console.error(`  • ${failure}`);
  console.error('\nSee https://github.com/adcontextprotocol/adcp-sdk-java/issues/41 for context.');
  process.exit(1);
}

console.log(`${relPath}: OK (${stepBodies.length} steps checked).`);
