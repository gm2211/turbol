
<!-- specify:begin:spec-workflow -->
## Maintain specs as you work

Canonical spec: "turbol.spec". Run commands from "." relative to this file.

- Read relevant spec areas and global constraints before editing. Use `specify spec guide` for structure and its capture, review, and reconcile workflow.
- Record explicit user decisions directly in the spec, even when no code changes. Keep stable behavior IDs; store exact quotations in source.text and a reference when available. Label proposals and assumptions in prose; never promote guesses into requirements.
- Keep one feature per area, short behavior descriptions, and detail in details/prose. Use `specify spec split --spec 'turbol.spec'` for oversized single-file specs.
- Update specs when intent changes. Never rewrite requirements to excuse incomplete implementation. Report unmet requirements in the handoff and issue tracker.
- Before implementation, review new intent against relevant behaviors, global constraints, and existing plans/tasks. Cite conflicting IDs and sources; resolve authorized changes, surface unresolved decisions, and continue independent work. This is agent review, not a semantic check performed by Specify.
- After implementation, reconcile affected and potentially regressed behavior IDs against current code and actual smoke/regression results, including the original bug reproduction. Report each as satisfied, gap, or unverified with evidence and revision. Add corrective work to the existing tracker, implement authorized fixes, and repeat; never use checked tasks or passing formal models as application proof.
- Keep the selected spec authoritative. Plans/tasks reference behavior IDs; do not create duplicate requirements or a separate evidence store. Put command/results and blockers in the existing PR or task tracker. Do not declare completion with unmet requirements or missing required verification.
- Before finishing, run `specify spec check --spec 'turbol.spec' --base BASE`. Use the task start commit or PR base. If intent is unchanged, pass `--reason 'why existing requirements still cover this change'` instead of making a token spec edit. Include that explanation in the PR.
- Run project tests separately. This check enforces spec lint and a recorded review reason or source change, not semantic correctness or execution proof.
- For properties linked from behaviors, run `specify formal check --spec 'turbol.spec'` with caller-installed Quint or Lean. A passing model check does not establish that the model captures prose or that the application satisfies it.
<!-- specify:end:spec-workflow -->
