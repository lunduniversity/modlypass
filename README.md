# modlypass

OpenAI-compatible gateway to coding models on shared GPUs, with per-user keys and fair-share budgets.

> **Status: design phase.** No code yet. This README is the design overview; a requirements
> document (`PRD.md`) will follow. modlypass is a pilot at Lund University and **not** an official
> university service.

## Why

Open-weight coding models such as Qwen3-Coder are good enough to drive a coding agent, but they need
a large GPU. Universities often have such GPUs in an HPC centre, but students do not have accounts
there, and HPC clusters are built for batch jobs, not for interactive services shared by a class.

modlypass sits in between. It lets a teacher give every student in a course a personal API key that
works directly in a coding agent such as [OpenCode](https://opencode.ai), while the GPU time stays
fairly shared, budgeted and accounted for.

## How it works

```
 student laptop                gateway host (department server)          HPC centre
 ┌──────────────┐  HTTPS       ┌───────────────────────────────┐  SSH    ┌────────────────────────┐
 │ OpenCode     │ ───────────► │ nginx (TLS)                   │ tunnel  │ GPU node (Slurm job)   │
 │ apiKey=…     │  /v1/chat/…  │   └─► modlypass               │ ──────► │  Ollama or vLLM        │
 └──────────────┘  streaming   │        keys, queue, budgets,  │         │  qwen3-coder:30b       │
                               │        usage log, tunnel mgr  │         │  listens on localhost  │
                               └───────────────────────────────┘         └────────────────────────┘
```

1. The teacher creates one API key per student from the course list and sends it out, together with
   a ready-to-paste `opencode.json` snippet.
2. The student's OpenCode sends OpenAI-compatible requests (`/v1/chat/completions`, streaming, tool
   calls) to modlypass with `Authorization: Bearer <key>`.
3. modlypass checks the key and the student's budget, queues the request fairly, and relays it
   through an SSH tunnel to the model server running as a job on an HPC GPU node. The streamed reply
   is passed back unchanged.
4. When the request finishes, modlypass records who, when, GPU time and token counts. It never stores
   prompts or code.

## Design decisions

These are the decisions so far. They will be refined in `PRD.md`.

### Students and keys

- **Students are indirect users.** They get no account at the HPC centre and no shell access. The
  teacher running the gateway holds the HPC account and is responsible for the service. Every request
  is traceable to one student through the gateway's log.
- **One API key per student.** Version 1: an admin command reads the course list (CSV with name and
  e-mail), creates the keys, and writes one ready-to-send message per student containing the key, a
  complete `opencode.json` snippet and a link to a getting-started page. Messages are e-mailed
  automatically if the host can send mail, otherwise by hand.
- **Only a hash of each key is stored.** A lost or leaked key is revoked and reissued with one
  command.
- **No central single sign-on.** An e-mail login link (one-time link to a course-list address) may
  come later.

### Fair sharing

- **At most one request in flight per student** (configurable), with a fair queue that serves active
  students in turn. This alone prevents most hogging.
- **Budgets in GPU-seconds**: how long each request held a slot on the GPU. This is the real cost.
  Tokens are a poor measure with coding agents, which resend the whole conversation and file
  contents on every turn.
- **Tokens are logged** for statistics, read from the usage data in the last streamed chunk.
- **`GET /me`** shows a student their own usage and remaining budget. Admins can top up budgets.
- **Group priority** (group A has priority in time slot X, but others still get unused capacity) is
  a possible later addition, only if measured queue waits call for it.

### Availability

- The model server runs as **chained Slurm jobs** during set service hours, so the GPU does not
  burn allocation at night. A job writes its node name and port to a file; modlypass reads it and
  moves the tunnel when the job changes.
- **Extra capacity during scheduled labs**: a second GPU job, ideally under a Slurm reservation.
  modlypass spreads requests over the available backends.
- **Honest unavailability.** When no model server is up, modlypass answers with a clear message
  ("model offline, back at 08:00") instead of letting requests time out, and `/health` reports the
  backend state it actually probed.

### Model and backend

- First model: **`qwen3-coder:30b`** (mixture of experts, about 3B active parameters, about 19 GB
  at 4-bit).
- First backend: **Ollama**, because it is quick to set up. **vLLM** will be measured before real
  use, since it batches many users' requests far better.
- The backend is anything OpenAI-compatible, so modlypass is not tied to one HPC centre or one
  inference server.

### Privacy and security

See [Security and privacy policy](#security-and-privacy-policy) below.

### Implementation

- **Scala 3 on the JVM, JDK only where possible**: the built-in HTTP server and `HttpClient` with
  virtual threads are enough for a streaming relay. No web framework unless one earns its place.
- Storage starts simple: an append-only usage log and a small users file, loaded into memory at
  startup. A database only when needed.
- Lessons carried over from [modly](https://github.com/bjornregnell/modly): health checks that
  probe the backend, failures reported as failures, never an empty success.

## Security and privacy policy

This policy is a draft for the pilot, written so that the HPC centre can assess the service. It
will be refined together with the centre.

### Responsibility

- One named person (the teacher running the gateway) holds the HPC account and is responsible for
  the service towards the HPC centre.
- Students are **indirect users**: they have no account at the HPC centre, no shell, no file
  access and no access to the scheduler. The only thing they can reach is a chat-completion API.
- Every request is traceable to one student through the gateway's log.

### Access control, step by step

| Stage | Who can use the model | How it is enforced |
|---|---|---|
| Iteration 0 (feasibility) | Only the teacher, on the gateway host | **Loopback-only relay** (below) |
| Iteration 1 and later | Holders of a valid personal API key | Key check on every request, HTTPS |

**Loopback-only relay.** Loopback is the address a computer uses to talk to itself (`127.0.0.1`
or `::1`, also called `localhost`). Loopback traffic never leaves the machine, so a request that
arrives from a loopback address was sent by a program on the gateway host itself. Until API keys
exist, the gateway serves its public info page and health check to anyone, but relays requests
under `/modlypass/v1/` **only** if they come from a loopback address. Everyone else gets
`403 Forbidden`, and nothing reaches the HPC centre.

Why this matters: without keys, the source address is the only access control. Bots scan the whole
internet for open ports all the time, and exposed language-model servers are actively sought out
and abused. An open relay would let strangers spend the HPC allocation, break the HPC centre's
rules, and make performance measurements meaningless.

The same principle applies at the HPC centre: the model server on the GPU node listens only on
`localhost`, so other cluster users cannot reach it. The gateway reaches it through an SSH tunnel,
which makes requests appear to come from loopback on the node. SSH login is therefore the access
control on that side.

**API keys** (from iteration 1). Every request must carry a personal key in the `Authorization`
header; requests without a valid key get `401`. Only a hash of each key is stored. Keys can be
revoked and reissued at any time. Keys never travel over plain HTTP: clients connect through HTTPS
or an SSH tunnel.

### Limiting misuse and cost

- At most one request in flight per student (configurable), served through a fair queue.
- A budget in GPU-seconds per student and period. When it is used up, requests are refused with a
  clear message.
- The model server runs only during agreed service hours, within the HPC project's allocation.

### What is logged

- **Logged:** student id (from the key), time, model, HTTP status, duration, queue wait,
  GPU-seconds and token counts.
- **Never logged or stored:** prompts, code, file contents, replies.
- Logs stay on the gateway host and are used only to run the service, share GPU time fairly and
  report usage to the HPC centre.

### Exposure

- The public surface is the gateway's HTTPS endpoint: an info page, a health check that reveals no
  internal details, and the OpenAI-compatible API behind key checks.
- The connection from the gateway to the HPC centre is an outgoing SSH tunnel. Nothing at the HPC
  centre is exposed to the internet.

### Reporting a security problem

Please report security problems privately through GitHub's
[security advisories](https://github.com/lunduniversity/modlypass/security/advisories/new) for
this repository, not in a public issue.

## Related projects

- [modly](https://github.com/bjornregnell/modly): a small single-user model router in front of a
  local Ollama. modlypass is a separate, multi-user sibling; modly is unchanged.
- [agaton](https://github.com/bjornregnell/agaton): manages several coding-agent accounts on the
  client side. A future agaton profile could point OpenCode at a modlypass key.

## First deployment

The pilot runs at the Department of Computer Science, LTH, Lund University, for an elective course
with about 25 students in study period 3 (January to March 2027). The backend is the COSMOS cluster
at [LUNARC](https://www.lunarc.lu.se/), part of [NAISS](https://www.naiss.se/), through the NAISS
project ICCIS (Interactive Code Generation for Students at LTH).

## Roadmap

- [x] Start [`PRD.md`](PRD.md) in reqT-lang: stakeholders, goals, releases (iteration 0, iteration
      1, course pilot), features, tests and risks.
- [ ] Iteration 0: bare-bones feasibility test (see `PRD.md`).
- [ ] Manual end-to-end test: model on an HPC GPU node, SSH tunnel, OpenCode.
- [ ] Slurm job script that publishes node name and port, plus job chaining.
- [ ] Gateway v1: streaming relay, key check, per-student concurrency limit, usage log, `/health`.
- [ ] Admin command: create keys from a course list and write the student messages.
- [ ] Budgets in GPU-seconds and `GET /me`.
- [ ] Tunnel manager that follows the Slurm job.
- [ ] Measure Ollama against vLLM under classroom load.
- [ ] Getting-started page for students.
- [ ] Pilot report after the course: GPU-hours per student, peak load, queue waits, feedback.

## License

Apache-2.0. See [LICENSE](LICENSE).
