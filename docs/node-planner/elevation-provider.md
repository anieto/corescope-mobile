# Node Planner elevation provider v1

Decision date: 2026-10-25

Node Planner v1 will use the Open-Meteo Elevation API backed by Copernicus DEM 2021 GLO-90. This
matches the provider and terrain resolution used by the audited MeshCore One implementation.

## Release mode

- Use `https://api.open-meteo.com/v1/elevation` while NodeScope remains noncommercial: no
  subscriptions or advertising. Voluntary donations are allowed by Open-Meteo's published terms.
- Reassess before any monetization change. Commercial use requires a suitable Open-Meteo
  subscription and the customer endpoint, or a separately operated provider.
- Never embed a paid API credential in the mobile binary. Route commercial requests through a
  controlled service or adopt an approved self-hosted deployment.

## Data contract

- WGS84 latitude/longitude input.
- Elevation in meters from the global 90 m GLO-90 digital surface model.
- At most 100 coordinates in one API request.
- Preserve request order and require exactly one finite elevation per requested coordinate.
- Record provider `open-meteo`, dataset `copernicus-dem-2021-glo-90`, nominal resolution `90 m`,
  fetch time, and whether each result came from network or cache.

## Product behavior

- Generate deterministic path samples including both endpoints and crossing the date line by the
  shorter longitude interval.
- Batch no more than 100 points; do not silently discard samples.
- Cache successful profiles by rounded coordinates, sampling policy and dataset version.
- Display cached/offline provenance and age.
- If terrain is unavailable or malformed, stop the analysis and explain that no estimate was
  produced. Never substitute zero elevation or silently downgrade to free-space-only results.
- Treat all output as planning estimates. GLO-90 is a surface model and may include vegetation and
  structures; its 90 m sampling cannot represent every local obstruction.

## Attribution and privacy

- Credit Open-Meteo and the Copernicus program in app acknowledgements and exported analyses.
- Identify modifications such as interpolation or derived path calculations as required by CC BY
  4.0.
- Explain before first use that path coordinates are sent to Open-Meteo for elevation lookup.
- Include the transfer and Open-Meteo's published coordinate-log retention behavior in the app's
  privacy documentation and store disclosures.

Official references:

- <https://open-meteo.com/en/docs/elevation-api>
- <https://open-meteo.com/en/terms>
- <https://open-meteo.com/en/pricing>
- <https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM>
