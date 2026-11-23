---
status: accepted
---

# The test pack's campaign_sample leaves the unbuilt depth as air

Issue #823. The server GameTest suite spent about a quarter of its time generating rock that no test reads: a `campaign_sample` chunk is 2352 blocks tall, and 1488 of those rows are unbuilt depth, solid `green_shale` under layer 2 (ADR 0059).

## Decision

- `deepcharter:campaign_sample`, the dimension the layer tests run in, takes its noise settings from the test pack's `deepcharter:campaign_sample`: the shipped `deepcharter:campaign` settings with the density below y -544 set to air. Two sections of the shipped rock stay under layer 2's floor.
- `deepcharter:campaign_full` is an exact copy of the shipped overworld (the settings are `deepcharter:campaign`). The tests whose subject is the generator itself run in it: `CampaignTerrainTest`, `CampaignCostTest`, `CampaignSurfaceParityTest`, and `CampaignWorldHashTest`.
- `SurfaceTerrainTest` pins both copies: `campaign_full` is the shipped overworld, `campaign_sample` is the shipped overworld with its own settings, and taking the one air term out of the sample's settings gives the shipped settings back exactly, so the copy cannot drift.

## Why

- Chunk generation is the suite's cost: the server thread waits on the world-gen workers, which sit at three busy threads on a 4-vCPU box for most of the run. A full-suite run took 14.2 min before and 10.9 min with the air depth (two baselines of 854 and 848 s against 657 s).
- No test of a pod, a crossing or a structure reads rock below layer 2: a pod that bores through layer 2's floor ends in the 32 kept rows, and the crossing tests move a floating player through the depth.
- The depth cannot be cheaper while staying rock: the cost is the per-block write and surface rule, which the density cannot skip.

## Consequences

- A test that asserts on the unbuilt depth, or on whole columns of generated terrain, runs in `campaign_full`. A new test in `campaign_sample` must not need rock below y -544.
- The client GameTests and evidence scenarios that use the `flat_all_dimensions` preset get the sample too. None reads the depth under layer 2 (checked in #823); one that does moves to `campaign_full`.
