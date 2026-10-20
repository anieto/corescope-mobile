# My Nodes / account contracts

All account fixtures here are synthetic. No live accounts or credentials are included.

- `evidence-cases.json`: 12 synthetic completeness/freshness cases consumed by both native suites.
  Times are epoch milliseconds; a null completeness represents an absent analytics context.

- `account-settings.json`: populated revision/generation and raw-string values, separate own-node
  and node-favorite categories, unknown own-node field, and unrelated theme key to preserve.
- `account-settings-empty.json`: revision zero, empty generation and null document.
- `account-merge-cases.json`: 11 language-neutral three-way-merge cases, verified against the
  pinned CoreScope web implementation using `tools/my-nodes-contract/verify-merge.mjs`.

The outer document values are strings, even when they contain JSON arrays. Do not flatten this
wire format into native favorites or drop unknown fields. Native tests should compare the same
expected results. App-specific import consent, account isolation and reset behavior need their
own tests; the upstream merge oracle does not prove those properties.
