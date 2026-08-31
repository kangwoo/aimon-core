# aimon-session-mongodb DDL

This directory ships `init.js`, the operator-applied DDL script for the
`aimon-session-mongodb` module. The runtime never executes this script —
it is applied manually once per cluster via:

```bash
mongosh "<connection-uri>" db/mongodb/init.js
```

For the full operational guide and the rationale for the manual-DDL stance see
`docs/design/session/backends.md` §3.4 and §7.
