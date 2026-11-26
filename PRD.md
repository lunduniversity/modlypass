# modlypass requirements

This is the requirements document for modlypass, written in [reqT-lang](https://github.com/reqT/reqT-lang),
a parseable markdown subset. The design overview and rationale are in [README.md](README.md).

Work is planned in iterations. Iteration 0 is a bare-bones feasibility test that can be done before
LUNARC has answered our policy and access questions. Iteration 1 starts when those answers are in.

## Context

* Product: modlypass has
  * Gist: OpenAI-compatible gateway to coding models on shared GPUs, with per-user keys and fair-share budgets.

* Stakeholder: teacher has
  * Gist: Runs the gateway, holds the HPC account and is responsible for the service.
  * Goal: fairSharing
  * Goal: lowOperatingEffort

* Stakeholder: student has
  * Gist: Uses a coding agent such as OpenCode on their own computer, as an indirect user of the HPC centre.
  * Goal: studentsUseCodingModels

* Stakeholder: hpcCentre has
  * Gist: Provides the GPUs (first deployment: LUNARC COSMOS, via the NAISS project ICCIS).
  * Goal: policyCompliance

## Goals

* Goal: studentsUseCodingModels has
  * Spec: Every student in a course can use a capable open-weight coding model from a coding agent, with streaming replies and tool calls, without an HPC account.
  * Why: Open-weight coding models need large GPUs that students do not have.

* Goal: fairSharing has
  * Spec: GPU time is shared fairly between students, budgeted, and accounted for per student.
  * Why: The GPU allocation is limited and one student must not be able to crowd out the others.

* Goal: policyCompliance has
  * Spec: Use of the HPC centre follows its rules: every request is traceable to a student, students get no shell access, and the allocation is respected.
  * Why: The teacher is personally responsible towards the HPC centre.

* Goal: privacy has
  * Spec: Only metadata is logged (who, when, model, GPU time, tokens, status). Prompts, code and replies are never stored.

* Goal: honestAvailability has
  * Spec: When the model is unavailable, clients get a clear message and health checks report the backend state actually probed.
  * Why: Lesson from modly, whose health check said ok for five weeks while every backend call failed.

* Goal: lowOperatingEffort has
  * Spec: Day-to-day running needs little manual work from the teacher.

## Releases

* Release: iteration0 has
  * Gist: Bare-bones feasibility test: the teacher's own OpenCode on the gateway host talks to a coding model on an HPC GPU node through a minimal relay.
  * Constraints: Only the teacher uses it. No API keys, so the relay endpoints accept requests from the gateway host itself (loopback) only. The SSH tunnel to the HPC centre is opened by hand with the normal 2FA login. Nothing needs answers from the HPC centre.
  * Feature: infoPage
  * Feature: loopbackOnlyRelay
  * Feature: manualModelServer
  * Feature: personalTunnel
  * Feature: streamingRelay
  * Feature: probingHealth
  * Feature: metadataLog
  * Feature: backendConfig
  * Feature: jobScript
  * Feature: vllmTrial
  * Test: opencodeEndToEnd
  * Test: baselineMeasurements

* Release: iteration1 has
  * Gist: Several users: API keys, per-key limits and usage, tested with at least two OpenCode clients at the same time.
  * Constraints: Starts when the HPC centre has answered the policy and access questions (see Risks). Keys must not travel over plain HTTP: clients on other machines connect through HTTPS (httpsExposure) or an SSH tunnel.
  * Feature: apiKeys
  * Feature: adminKeyCommand
  * Feature: perKeyConcurrency
  * Feature: fairQueue
  * Feature: perKeyUsage
  * Test: twoClientsConcurrently

* Release: coursePilot has
  * Gist: Ready for about 25 students in study period 3 (January to March 2027).
  * Feature: gpuSecondBudgets
  * Feature: meEndpoint
  * Feature: tunnelManager
  * Feature: offlineMessage
  * Feature: httpsExposure
  * Feature: studentMessages
  * Feature: multiBackend
  * Feature: gettingStartedPage

* Release: iteration0 precedes Release: iteration1
* Release: iteration1 precedes Release: coursePilot

## Iteration 0: feasibility

* Feature: manualModelServer has
  * Spec: Ollama with qwen3-coder:30b runs on an HPC GPU node in an interactive job, with a context length of at least 64k tokens, and reports 100% GPU in `ollama ps`.
  * Comment: An interactive session on a GPU partition meant for interactive use (gpua40 at LUNARC) is fine for this test.

* Feature: personalTunnel has
  * Spec: From the gateway host, an SSH tunnel through the HPC login node makes the model server reachable on a local port. The teacher enters the 2FA code once; SSH connection reuse (ControlMaster) keeps it open.

* Feature: streamingRelay has
  * Spec: A minimal Scala 3 server on the JDK (built-in HTTP server and HttpClient, virtual threads, no web framework) that forwards every request under /modlypass/v1/ to the configured backend and passes streamed replies back chunk by chunk, unbuffered and unchanged.
  * Why: The relay is the core of the gateway. Proving that streaming and tool calls pass through unchanged is the main feasibility question.

* Feature: probingHealth has
  * Spec: GET /modlypass/health probes the backend's model list and answers 200 with what it found, or 503 with the reason. It also reports version, build time and uptime. It is public, so it reveals no internal details such as node names.

* Feature: infoPage has
  * Spec: GET /modlypass/ serves a public static page explaining what modlypass is, with a link to the repository.

* Feature: loopbackOnlyRelay has
  * Spec: Until API keys exist, requests under /modlypass/v1/ are served only if they come from a loopback address; all others get 403.
  * Why: The gateway host has ports open to the internet. An open relay would let anyone spend the HPC allocation and would break the HPC centre's rules.

* Feature: metadataLog has
  * Spec: One line per request in an append-only log: time, path, model, HTTP status, duration, time to first byte, and token counts when the backend reports them. No prompt or reply content.

* Feature: backendConfig has
  * Spec: The backend base URL and the server's PORT are given on the command line or in a small config file, with a sensible default port. All endpoints live under the path prefix /modlypass so that a reverse proxy can map it without rewriting.

* Feature: jobScript has
  * Spec: An sbatch script that starts the model server on a GPU node and writes the node name and port to a file in project storage, so the tunnel can be pointed at it.
  * Comment: Stretch goal for iteration 0. Real service runs use a batch partition (gpua100 or gpua100i at LUNARC).

* Feature: vllmTrial has
  * Spec: The official vLLM image (pulled with Apptainer) serves the same model on a GPU node, and the relay works against it unchanged.
  * Comment: Stretch goal for iteration 0. Needed before the course to judge capacity.

* Feature: streamingRelay requires Feature: backendConfig
* Feature: probingHealth requires Feature: backendConfig
* Feature: streamingRelay requires Feature: personalTunnel
* Feature: personalTunnel requires Feature: manualModelServer

* Feature: streamingRelay helps Goal: studentsUseCodingModels
* Feature: probingHealth helps Goal: honestAvailability
* Feature: loopbackOnlyRelay helps Goal: policyCompliance
* Feature: metadataLog helps Goal: privacy
* Feature: metadataLog helps Goal: fairSharing

* Test: opencodeEndToEnd has
  * Spec: OpenCode on the gateway host, configured with baseURL http://localhost:PORT/modlypass/v1 as an OpenAI-compatible provider, completes a small coding task that needs tool calls (read a file, edit it, run a command), with replies streaming visibly.

* Test: baselineMeasurements has
  * Spec: Record time to first token and tokens per second for a short prompt and for a prompt near 32k tokens, GPU memory use, and model load time. These numbers set the capacity assumptions for later iterations.

* Test: opencodeEndToEnd verifies Feature: streamingRelay
* Test: baselineMeasurements verifies Goal: fairSharing

## Iteration 1: several users

* Feature: apiKeys has
  * Spec: Every request must carry a valid key in the Authorization header. Only a hash of each key is stored. A key can be revoked.

* Feature: adminKeyCommand has
  * Spec: An admin command creates, lists and revokes keys, one per user.

* Feature: perKeyConcurrency has
  * Spec: At most a configurable number of requests in flight per key (default 1). Further requests wait.

* Feature: fairQueue has
  * Spec: Waiting requests are served in turn between keys, not first come first served.

* Feature: perKeyUsage has
  * Spec: The metadata log records the key's owner, and usage can be summed per user.

* Feature: perKeyConcurrency requires Feature: apiKeys
* Feature: fairQueue requires Feature: perKeyConcurrency
* Feature: perKeyUsage requires Feature: apiKeys

* Feature: apiKeys helps Goal: policyCompliance
* Feature: fairQueue helps Goal: fairSharing
* Feature: perKeyUsage helps Goal: fairSharing

* Test: twoClientsConcurrently has
  * Spec: Two OpenCode instances with different keys work at the same time. Neither is blocked for long, a request without a valid key is refused with 401, and usage is reported per key.

* Test: twoClientsConcurrently verifies Goal: fairSharing

## Course pilot (sketch)

* Feature: gpuSecondBudgets has
  * Spec: Each user has a budget in GPU-seconds per period. When it is used up, requests are refused with a clear message. The admin can top up a budget.

* Feature: meEndpoint has
  * Spec: GET /me shows a user their own usage and remaining budget.

* Feature: tunnelManager has
  * Spec: The gateway reads the node and port file written by the job script and moves the SSH tunnel when the job changes.

* Feature: offlineMessage has
  * Spec: When no backend is up, clients get a clear reply such as "model offline, back at 08:00" instead of a timeout.

* Feature: httpsExposure has
  * Spec: The gateway is reachable over HTTPS through the department's nginx, with buffering off and long read timeouts so streaming works.

* Feature: studentMessages has
  * Spec: From a course list (CSV with name and e-mail) the admin command writes one message per student with the key and a ready-to-paste opencode.json.

* Feature: multiBackend has
  * Spec: Requests are spread over several model servers, e.g. a second GPU during scheduled labs.

* Feature: gettingStartedPage has
  * Spec: A short page telling students how to install OpenCode and use their key.

* Feature: gpuSecondBudgets requires Feature: perKeyUsage
* Feature: tunnelManager requires Feature: jobScript
* Feature: studentMessages requires Feature: adminKeyCommand

* Feature: gpuSecondBudgets helps Goal: fairSharing
* Feature: tunnelManager helps Goal: lowOperatingEffort
* Feature: offlineMessage helps Goal: honestAvailability
* Feature: studentMessages helps Goal: lowOperatingEffort

## Risks

* Risk: studentUseNotAllowed has
  * Spec: The HPC centre does not allow indirect use by students.
  * Comment: Blocks iteration 1 for real students; iteration 0 is unaffected.

* Risk: twoFactorBlocksService has
  * Spec: The tunnel needs a 2FA code on every reconnect, so the service cannot stay up unattended.
  * Comment: Mitigation asked for: a restricted service account without 2FA.

* Risk: allocationTooSmall has
  * Spec: Full-node charging on gpua100 gives about 208 GPU-hours a month, which may not cover service hours plus labs.

* Risk: projectEndsBeforeCourse has
  * Spec: The current allocation appears to end in February 2027, before the course ends in March.

* Risk: ollamaTooSlowForClass has
  * Spec: Ollama on one GPU cannot serve 10 to 15 simultaneous coding-agent users with large contexts.
  * Comment: Mitigation: vllmTrial and multiBackend.

* Risk: studentUseNotAllowed hurts Goal: policyCompliance
* Risk: twoFactorBlocksService hurts Goal: lowOperatingEffort
* Risk: allocationTooSmall hurts Goal: fairSharing
* Risk: ollamaTooSlowForClass hurts Goal: studentsUseCodingModels
* Risk: projectEndsBeforeCourse hurts Goal: studentsUseCodingModels
