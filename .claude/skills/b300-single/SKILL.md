---
name: b300-single
description: Use the b300-single SSH alias to inspect or work on the user's b300 Slurm cluster projects, including initial SSH setup, remote paths, file transfer, and safe job operations.
---

# b300-single

Use this skill when the user explicitly invokes `$b300-single` for work on b300-single. Treat each request's named project and permitted actions as the scope; this skill itself grants no permission to edit files, submit jobs, or cancel jobs.

## 1. Connect from the local machine

- Check whether the local `~/.ssh/config` already defines `Host b300-single` and whether its `IdentityFile` exists. Preserve a working entry and key; never overwrite or regenerate either just to follow this guide.
- If setup is requested and the entry is missing, use this local SSH config (adjust the identity path only if the user's actual key differs):

  ```sshconfig
  Host b300-single
      HostName air-b300.krafton-ml.net
      User hyunjun.lee
      Port 2222
      IdentityFile ~/.ssh/id_ed25519_codex_b300
      IdentitiesOnly yes
  ```

- If the private key is missing, ask which existing credential to use or, with authorization, generate a new Ed25519 key and arrange for its **public** key to be authorized on b300. Never print, copy into chat, or commit a private key. Keep `~/.ssh` at mode 700 and private keys and `~/.ssh/config` at mode 600.
- Check the resolved alias with `ssh -G b300-single`, then test non-interactively with `ssh -o BatchMode=yes -o ConnectTimeout=10 b300-single 'id -un; hostname; pwd'`. A failed login is an access or network issue, not a reason to recreate project environments.

## 2. Locate the requested project

- The local Mac and remote b300 filesystem are separate. Remote project directories are under `/KRAFTON/WORKSPACE/wbl-workspace/prj_model_data/usrs/hyunjun.lee/`; do not assume any particular NeMo-RL checkout, branch, or job ID.
- Resolve the exact project from the user's request and verify it exists before acting. If multiple checkouts match and the target matters, ask which one. Use absolute paths or an explicit remote `cd`; every `ssh` command starts a new shell, so the previous working directory and shell variables do not persist.
- For a read-only status check, for example: `ssh b300-single 'squeue -u hyunjun.lee'`. For repository work, inspect its branch, worktree status, instructions, and relevant logs before changes.
- For file transfer, use `scp` with explicit local and remote paths. Inspect the source and destination first; don't overwrite a remote file merely to synchronize a checkout. Never transfer API keys or credentials into logs or chat.

## 3. Work safely with Slurm and long-running jobs

- Discover current partitions, nodes, job ownership, and job state from `sinfo`, `squeue`, `scontrol`, and `sacct`; old node lists and allocations are not reusable facts. Prefer the user's requested partition. The user's standing preference for CPU diagnostic jobs is `wbl`, but verify availability and current instructions.
- A read-only request stays read-only. Submit, cancel, preempt, or edit a job only when requested. Before `scancel`, identify the exact job ID, owner, and purpose; never target another user's job or use broad globs. Preserve unrelated running jobs and checkouts.
- When submitting an authorized job, make the project path, config, partition, resources, log path, and expected checkpoint path explicit. Do not build or rebuild `uv`/venvs unless the user asks or the verified task truly requires it. Reuse existing environments only after checking their paths and compatibility.
- For a long-running command, return the actual job/session ID and log path. Monitor with bounded, read-only checks and report meaningful state changes or errors; do not claim a run succeeded merely because allocation or server startup succeeded.

## 4. Report

State which remote project/branch was used, what was observed or changed, job IDs when relevant, verification performed, and any unresolved blocker. Distinguish local files from remote files and verified facts from inferences. Never expose private keys, PATs, or API-key contents.
