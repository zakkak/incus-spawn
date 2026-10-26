# Running Orca's agents in isx

[Orca](https://github.com/stablyai/orca) runs several coding agents side by side, each in a workspace of its own, and tracks them in one window.
With isx, each Orca workspace gets an isx instance of its own, so its agents work on a machine that holds none of your credentials.
Orca still shows their terminals, diffs and status as it does for any workspace.

Orca's per-workspace environments, which it calls Cloud VM, run commands you name to make and remove a machine for each workspace.
`isx orca` provides those commands.
It branches an instance from a template you prepared, and tells Orca how to reach it over SSH.

## Setting it up

Setting it up takes a template with the `orca` tool and an agent, and the isx plugin for Orca.

1. Define a template with the `orca` tool and the agent you run in Orca.
   The `orca` tool brings the SSH server Orca connects through, and the commands its server needs in the instance.
   Listing your repository under `repos` is optional, and makes each workspace start from the template's clone instead of a fresh one.

   ```yaml
   name: tpl-app
   parent: tpl-java
   tools: [orca, claude]
   repos:
     - url: https://github.com/acme/app.git
       path: ~/app
   ```

2. Build it with `isx build tpl-app`.
3. Write the plugin with `isx orca plugin`, which puts it in `~/.config/incus-spawn/orca-plugin`.
4. In Orca, under Settings, Plugins, turn on the plugin system and install the plugin from that folder.
   Orca shows the four commands it runs; approve them.
5. Turn on **Cloud VM** under Settings, Experimental.
6. Create a workspace and choose **isx sandbox** under **Run on**.

## The isx plugin for Orca

The plugin offers **isx sandbox** in every repository you open in Orca, with nothing to add to the repository.
It holds no code: only Orca's plugin manifest and one recipe, whose four commands are `isx orca create`, `suspend`, `resume` and `destroy`.
Orca runs them with your login shell's `PATH`, so they find `isx` wherever it is installed.

The plugin's files change only when isx changes the recipe.
Orca asks you to approve a plugin again whenever its files change, so writing it again after an upgrade asks nothing new unless they did.
It needs Orca 1.4.160 or later.

## Choosing the template

The plugin's recipe is the same for every repository, so `isx orca create` chooses the template, in this order:

1. The template `--from` names.
2. The template the checkout's `incus-spawn.yaml` names.
3. Of the templates built with the `orca` tool, the one that clones the repository's remote.
4. The only template built with the `orca` tool.

With none, or with several and no clone to tell them apart, it refuses and says which templates it found.

## A recipe in the repository instead

A recipe in the repository's `orca.yaml` suits a team that shares it, or a repository that needs other options.
Orca lists it only once `orca.yaml` is on the repository's main branch, and a recipe named `isx` there takes the plugin's place in that repository.

```yaml
environmentRecipes:
  - id: isx
    name: isx sandbox
    create: isx orca create --from tpl-app
    suspend: isx orca suspend
    resume: isx orca resume
    destroy: isx orca destroy
```

`orca vm recipe doctor isx --provision` checks such a recipe end to end: it creates an instance, reads the result and destroys the instance again.
It runs on your computer and costs nothing.

## Credentials

Nobody logs in inside an Orca workspace's instance.
The isx proxy adds the agent's account to its requests as they leave, as in any isx instance, and signs in to GitHub for the clone.

If the agent's account is not set up yet, run `isx account add <namespace>`, such as `isx account add claude`, on your computer.
Running the agent's own login inside the instance would put a credential where isx keeps none.

## What `isx orca create` does

`isx orca create` makes one instance per Orca workspace, ready before Orca connects.

- It branches the template as `isx branch` does, with the template's network mode, accounts and limits.
  The instance is named `orca-<workspace>-<suffix>`, where the suffix tells apart two workspaces of one name.
- It prepares the checkout Orca opens, as the next section describes.
- It prints the SSH connection Orca opens.
  Orca connects as `agentuser` through `isx ssh-proxy`, which tunnels through Incus, with the key isx manages.
- When a step fails after the branch exists, it deletes the instance again, since Orca never hears of it.

Orca then installs its own server in the instance over SSH, and runs its terminals and agents there.
Agents started in Orca's terminals get the instance's login environment, so the isx proxy signs them in to their accounts.

Orca's server needs `tar` and `awk` in the instance, which the `orca` tool installs.
`isx orca create` warns when the template lacks the tool, and refuses a template with no SSH server.

## The checkout

Orca opens one checkout in the instance, and adds a Git worktree there for each workspace.
`isx orca create` finds or makes that checkout from the remote of your repository's checkout on the host, `origin` or its only remote.

| Case | What `isx orca create` does |
|------|-----------------------------|
| The template cloned the same repository | Uses that clone where it is, and fetches it |
| It did not | Clones the repository at `~/<name>` over HTTPS |
| You give `--project-root <path>` | Uses that checkout, cloning the repository there if it is missing |

The instance clones over HTTPS, through the isx proxy, which signs in to GitHub with your account.
An SSH remote such as `git@github.com:acme/app.git` is cloned from the same path over HTTPS, because the instance holds no SSH key.
A repository with no network remote needs `--project-root`, pointing at a checkout the template made.

Commits you have not pushed are not in the instance, which clones from the remote.

A recipe with `checkoutMode: provisioned-root` also works.
Orca then sends the branch and the exact commit to start from, and `isx orca create` checks out that branch at that commit itself.

## Suspend, resume and destroy

Orca runs these commands when a workspace sleeps, wakes or is deleted, with a JSON payload on stdin.

| Command | What it does |
|---------|--------------|
| `isx orca suspend` | Stops the instance |
| `isx orca resume` | Starts the instance, and prints the connection again |
| `isx orca destroy` | Deletes the instance; one already gone is success |

An instance that stops while Orca still counts its workspace as awake, as after a restart of your computer, starts again when Orca reconnects.

`isx orca create` stamps the instance with Orca's id for the workspace.
The other three act only on an instance carrying the id their payload names, so a payload cannot stop or delete an instance Orca did not get from isx.
A branch you make of such an instance does not carry the stamp.

## Options

`isx orca create` takes the options of `isx branch` that suit an instance nobody opens a shell in.

| Option | What it does |
|--------|--------------|
| `--from <template>` | The template or instance to branch. Default: as [Choosing the template](#choosing-the-template) says |
| `--project-root <path>` | The checkout in the instance Orca opens, absolute or under `~/` |
| `--account <ns>=<account>` | Use this [credential account](CONFIGURATION.md#credential-accounts); repeatable |
| `--proxy-only` | Restrict the instance's network to the isx proxy |
| `--cpu <N>`, `--memory <size>`, `--disk <size>` | Resource limits, with the defaults of `isx branch` |

Stdout holds only the JSON object Orca reads.
Progress and errors go to stderr, which Orca shows while it creates the workspace.

## Checking a workspace

Each workspace's instance is an ordinary isx instance, so the usual commands reach it.

| Command | What it shows |
|---------|---------------|
| `isx list` | The instance, named `orca-<workspace>-<suffix>` |
| `ssh <instance>` | A shell over the same SSH connection Orca uses |
| `isx shell <instance>` | A shell in the instance |
| `isx proxy activity` | What the agent sent through the proxy |

## Troubleshooting

Orca shows the output of `isx orca create` while it creates a workspace, and keeps it in the recipe doctor's transcript.

| Message | Fix |
|---------|-----|
| `has no SSH server, which Orca connects through` | Add the `orca` tool to the template and rebuild it |
| `tar: command not found`, or the installed orcad build hash cannot be verified | Add the `orca` tool to the template and rebuild it |
| `has no remote isx can clone` | Push the repository, list it in the template's `repos`, or give `--project-root` |
| `the isx proxy is not running` | Run `isx proxy start`, or `isx doctor` for the cause |
| `no template or instance '<name>' to branch` | Build the template, or correct `--from` |
| `no template is built with the orca tool` | Add the `orca` tool to a template and build it |
| `several templates are built with the orca tool` | Name one in the repository's `incus-spawn.yaml`, or in a recipe of its own `orca.yaml` |
| `was not created by isx orca for this Orca runtime` | The payload names an instance isx did not make for it, which isx leaves alone; remove it yourself with `isx destroy` if it is yours |
