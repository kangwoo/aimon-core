# aimon-session-mongodb DDL

This directory ships `init.js`, the operator-applied DDL script for the
`aimon-session-mongodb` module. The runtime never executes this script —
it is applied manually once per cluster via:

```bash
mongosh "<connection-uri>" db/mongodb/init.js
```

## Replica set

The signal bus uses Change Streams, which require a replica set even on a single
node. `init.js` warns when `replSetGetStatus` fails but does not initiate one.
For development:

```bash
mongod --replSet rs0 --dbpath /tmp/mongo
mongosh --eval 'rs.initiate()'
```

Production: three members (PSS), oplog sized for at least 24 hours of peak
writes, `?w=majority&readConcernLevel=majority` on the URI, primary reads.

For the rationale behind the manual-DDL stance and these requirements see
`docs/design/session/backends.md` §2.4 and §4.
