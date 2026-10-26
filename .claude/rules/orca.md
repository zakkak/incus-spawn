---
paths:
  - cli/src/main/java/dev/incusspawn/orca/**
  - cli/src/test/java/dev/incusspawn/orca/**
  - cli/src/main/java/dev/incusspawn/command/OrcaCommand.java
  - docs/ORCA.md
---

# Orca environment recipes (`isx orca`)

`isx orca create|suspend|resume|destroy` implements the four commands of an Orca environment recipe (Orca's "Cloud VM" feature), so each Orca workspace gets an isx instance.
DESIGN.md "Orca environment recipes: an instance per workspace" has the rationale; docs/ORCA.md is the user page.

The contract is Orca's, in stablyai/orca:
- `src/shared/ephemeral-vm-recipe-process.ts`: the `ORCA_*` variables `create` gets (`ORCA_VM_INSTANCE_ID`, `ORCA_WORKSPACE_NAME`, `ORCA_REPO_PATH`, `ORCA_RECIPE_RESULT_SCHEMA_VERSION`, and for provisioned roots `ORCA_REPO_URL`, `ORCA_REPO_REF`, `ORCA_REPO_REF_HEAD`, `ORCA_REPO_BRANCH`).
- `src/shared/ephemeral-vm-recipes.ts`: the result schema, `.strict()` throughout, so an unknown field fails the whole result.
  `OrcaRecipeTest.theResultHoldsOnlyWhatOrcasStrictSchemaAccepts` pins the key sets; change it only together with Orca's schema.
- `src/shared/ephemeral-vm-recipe-lifecycle-payload.ts`: the stdin payload of `suspend`, `resume` and `destroy`.
  `resume` must print a result again.

Layout:
- `OrcaRecipe`: the contract (context from the environment, instance name, guest clone URL, result, payload).
- `OrcaCheckout`: where the checkout lives in the instance and the script that prepares it.
- `OrcaRuntime`: the four flows on an `OrcaBackend`; `OrcaRuntimeTest` drives them with a fake.
- `IncusOrcaBackend`: the real backend, through `BranchFlow`, `InstancePrep.prepare` and `InstanceDestroyer`, under the instance lock manager.

Rules:
- Stdout is the protocol: `OrcaCommand` installs `StdioGuard` first, and writes only the result to `guard.protocolOut`.
- Every refusal runs before the copy (preflight, the source's `sshd`, a remote the instance can clone); after it, a failure deletes the branch by the stamp check.
- Lifecycle commands act only on an instance whose `orca-runtime` stamp equals the payload's runtime; `destroy` of a missing instance succeeds.
- The instance clones over HTTPS only (`OrcaRecipe.guestCloneUrl`): never a local path, `file://` or a remote helper, and never with user info from the host's remote.
