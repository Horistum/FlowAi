# Architecture Delta Checklist

Use this checklist before accepting any change.

## Purpose Alignment

- [ ] Does the change support Flow as an AI-first standardization layer?
- [ ] Does the change improve intent processing, standard contract clarity, compatibility verification, safety, or debt reduction?
- [ ] Is the change part of the approved roadmap?

## Boundary Checks

- [ ] Does Flow Core remain non-runtime?
- [ ] Does public Flow syntax remain target-neutral?
- [ ] Are target-specific details kept in generators or projection layers?
- [ ] Does the change avoid SDK-first drift?
- [ ] Does the change avoid plugin lifecycle drift?

## Standard Model Checks

- [ ] Is there a single source of truth for the affected concept?
- [ ] Are compatibility aliases justified and documented?
- [ ] Are old concepts removed, migrated, or clearly deprecated?

## Test and Conformance Checks

- [ ] Are positive behavior scenarios covered?
- [ ] Are forbidden or unsafe scenarios covered?
- [ ] Are conformance vectors updated where needed?
- [ ] Is fallback lowering prevented?

## Documentation Checks

- [ ] Is public behavior documented?
- [ ] Is the release report updated?
- [ ] Are known limitations stated?
