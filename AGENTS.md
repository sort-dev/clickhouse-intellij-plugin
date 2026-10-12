# Agent guide

- Work on `grand-unification`; never push `main`, force-push or publish.
- Build from shared Brikk SQL adapters, not sibling plugin copies. Library gaps
  belong upstream; do not edit other repositories or add local substitutes.
- Use idiomatic Kotlin 2.4.x. Java-compatibility annotations only when IntelliJ requires them.
- Test real platform/parser/console behavior on 261, 262 and 263. Assert ranges and
  inserted text, not presentation. Background work must fail on logged errors.
- Keep scratch files under `build/`. Finish all builds before reporting.
- Commit each verified item and push with upstream. Use the owner's git identity
  and add `Co-Authored-By: GPT-6.1 Sol <noreply@openai.com>`.
