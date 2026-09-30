# Ops Runbook

Test-only knowledge base document, absent from the shipped corpus on purpose.

## Database failover

Database failover is approved for 02:00-04:00 UTC only, and only after the on-call lead records the change
in the incident channel.

## Escalation

Page the secondary owner after fifteen minutes without a response from the primary.

## Replica restore

Restore the read replica from the last verified snapshot before promoting a new primary.
