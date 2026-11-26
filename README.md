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

- Logged: student id, time, model, GPU-seconds, token counts, queue wait, status. **Not logged:**
  prompts, code, replies.
- The model server listens only on `localhost` on the GPU node and is reached through the SSH
  tunnel, so other cluster users cannot reach it.
- The only exposed endpoint is the OpenAI-compatible API behind HTTPS.

### Implementation

- **Scala 3 on the JVM, JDK only where possible**: the built-in HTTP server and `HttpClient` with
  virtual threads are enough for a streaming relay. No web framework unless one earns its place.
- Storage starts simple: an append-only usage log and a small users file, loaded into memory at
  startup. A database only when needed.
- Lessons carried over from [modly](https://github.com/bjornregnell/modly): health checks that
  probe the backend, failures reported as failures, never an empty success.

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

- [ ] Write `PRD.md`: stakeholders, goals, features and acceptance targets, refining the decisions
      above.
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
