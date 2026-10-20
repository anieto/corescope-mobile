# My Nodes contract tools

`probe.py` makes anonymous HTTPS GET requests to the four established test sources. It accepts
only its fixed host allowlist, disables redirects, sends no credentials, caps each source at 12
requests, limits response size, and stops that source on HTTP 429. It outputs shapes, counts,
byte sizes and timing, not returned node IDs, names, coordinates, messages or account data.

```sh
python3 tools/my-nodes-contract/probe.py --host analyzer.meshtexas.org
python3 tools/my-nodes-contract/probe.py --host beacon.meshtexas.org
```

Do not interpret a first-page sample as a complete catalog, retained time coverage, or app cache
benchmark. A repeat analytics request measures another HTTP response only.

`verify-merge.mjs` checks the shared synthetic golden cases against the public web merge function
from CoreScope commit `9dbc287579a237ffa744dd0c91fa7227d09763ac`. Download its
`public/settings-sync.js` to a temporary file, then run:

```sh
node tools/my-nodes-contract/verify-merge.mjs /tmp/settings-sync.js
```

The verifier checks SHA-256 before evaluating the script in a timed context with authentication
disabled. It executes no login, browser or network workflow. The upstream source is not vendored.

```sh
python3 -m unittest discover -s tools/my-nodes-contract -p 'test_*.py'
```
