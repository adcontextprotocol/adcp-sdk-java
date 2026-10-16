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
// Steps in this workflow sit at six-space indentation; split on that so each
// assertion can look at one step at a time.
const stepStart = /^ {6}- (?:name|uses):/;
const steps = [];
for (const line of lines) {
  if (stepStart.test(line)) steps.push([]);
  if (steps.length > 0) steps[steps.length - 1].push(line);
}
const stepBodies = steps.map((s) => s.join('\n'));

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

// ── ARGUS_MODEL is declared and verified before the review runs ──────────────
const modelEnv = /^\s*ARGUS_MODEL:\s*(\S+)\s*$/m.exec(source);
if (!modelEnv) {
  fail(`${relPath}: no \`ARGUS_MODEL:\` env entry declaring the review model.`);
} else if (!/^claude-[a-z0-9-]+$/.test(modelEnv[1])) {
  fail(`${relPath}: ARGUS_MODEL \`${modelEnv[1]}\` does not look like an Anthropic model ID.`);
}

const verifiesModel = stepBodies.some(
  (step) =>
    step.includes('api.anthropic.com/v1/models/${ARGUS_MODEL}') &&
    /exit 1/.test(step) &&
    !/continue-on-error:\s*true/.test(step),
);
if (!verifiesModel) {
  fail(
    `${relPath}: no job-failing step checks ARGUS_MODEL against ` +
      `https://api.anthropic.com/v1/models/ before the review runs.`,
  );
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
  if (!/if:\s*always\(\)/.test(gate)) {
    fail(
      `${relPath}: the "did Argus post a review" gate must run with \`if: always()\`, ` +
        `otherwise an earlier failure skips it and the job still passes.`,
    );
  }
  if (/continue-on-error:\s*true/.test(gate)) {
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
