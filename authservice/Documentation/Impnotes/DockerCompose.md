# Getting to know docker-compose.yml

Our file, for reference:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: authservice
      POSTGRES_USER: authservice
      POSTGRES_PASSWORD: authservice
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

volumes:
  postgres-data:
```

---

## Q1: What is the purpose of this file?

**It's a recipe for standing up the exact "supporting cast" our app depends on — Postgres and Redis — with one command, the same way every time.**

`authservice` itself is just Java code. It doesn't *contain* a database or a cache — it expects to find one running somewhere and connect to it (that's what `spring.datasource.url` and `spring.data.redis.host` in `application.properties` point at). `docker-compose.yml` is what actually makes that "somewhere" exist: it tells Docker "run a Postgres server and a Redis server, on these ports, with this username/password, and keep their data around between restarts."

Run `docker compose up -d` → both servers are alive on `localhost` in a few seconds → `./mvnw spring-boot:run` now has something to talk to.

---

## Q2: What problem does it actually solve?

Without it, "getting a database running on my laptop" is a small project of its own:

- Install Postgres itself (a real installer, not a library your build tool fetches).
- Remember to also start it as a background service.
- Manually create the `authservice` database, plus a `authservice` user with a password, and grant that user access — by hand, in `psql`.
- Do the same again for Redis (separate install, separate "start the service" step).
- Make sure the *versions* you installed match what a teammate installed, so "works on my machine" doesn't bite you later.
- When you're done, remember how to fully stop and clean these up without breaking some other project on the same machine that also uses Postgres.

That's five or six manual, easy-to-get-wrong steps, repeated by every person who clones this repo, on every machine, forever. `docker-compose.yml` collapses all of that into: read one file, run one command. The versions (`postgres:16`, `redis:7`) are pinned *in the file itself*, so everyone gets identical servers — no "which Postgres version do you have" debugging.

This is the same reason `pom.xml` exists for Java dependencies: instead of everyone hand-downloading `.jar` files, one file declares exactly what's needed and a tool provisions it consistently. `docker-compose.yml` is that idea, applied to *infrastructure* (databases, caches) instead of *libraries*.

---

## Q3: What would our implementation look like *without* Docker?

To get to the same end state — a Postgres server on port 5432 with an `authservice` database, and a Redis server on port 6379 — by hand on macOS, you'd do something like:

```bash
# Install the actual database software on your machine
brew install postgresql@16
brew services start postgresql@16     # start it now, and on every login

# Create the DB + user by hand, using Postgres's own admin tool
psql postgres
  CREATE DATABASE authservice;
  CREATE USER authservice WITH PASSWORD 'authservice';
  GRANT ALL PRIVILEGES ON DATABASE authservice TO authservice;
  \q

# Repeat the whole exercise for Redis
brew install redis
brew services start redis
```

Notice what changed in *kind*, not just in step count:

- **It's permanent and global.** `brew install` puts Postgres and Redis directly onto your machine, running as system services — not scoped to this one project. If another project on your laptop also wants Postgres, and needs a different major version, you now have a version conflict to manage yourself.
- **Cleanup is manual and fuzzy.** "I want this project's database gone" means remembering `DROP DATABASE`, or uninstalling Postgres entirely and hoping nothing else needed it. With Compose, `docker compose down -v` deletes exactly this project's containers and data, and nothing else on your machine even notices.
- **It doesn't travel with the repo.** None of those `brew`/`psql` commands live in git. A new teammate reads no instructions for it unless someone writes a separate setup doc — and that doc drifts out of date. `docker-compose.yml` *is* the instructions, checked into the repo, and it can't silently go stale the way prose can.
- **The app's `application.properties` would have to guess.** Right now `spring.datasource.username=authservice` / `password=authservice` works because the compose file creates exactly that user with exactly that password. Without Compose, that only works if you typed the matching `CREATE USER` command yourself — one more thing to keep in sync by hand.

So: functionally, both routes end with "a Postgres and a Redis on `localhost`." The difference is that one way is a reusable, disposable, version-pinned recipe living in the repo, and the other is a pile of manual, machine-specific setup that lives only in your terminal history (and in your head).

---

## Q4: Breaking down every instruction in the file

```yaml
services:
```
The top-level "here's what I want running" list. Everything indented under it is one thing Docker will start as its own isolated process (a **container**).

```yaml
  postgres:
```
The name we're giving *our* service. This is arbitrary — we chose `postgres` because it's a Postgres server — but it's also how the app *finds* it: inside Docker's private network, this container is reachable at the hostname `postgres`. (We're not actually using that hostname yet, since our Spring Boot app runs directly on the host machine, not inside Docker — it reaches Postgres via `localhost`, which works because of the `ports:` mapping below.)

```yaml
    image: postgres:16
```
"Don't build anything custom — pull the official, pre-built Postgres image, version 16, from Docker Hub, and run that." This one line replaces the entire "download the Postgres installer, run it, configure it" process. Pinning `16` (not just `postgres:latest`) means the version never silently changes underneath you.

```yaml
    environment:
      POSTGRES_DB: authservice
      POSTGRES_USER: authservice
      POSTGRES_PASSWORD: authservice
```
Environment variables passed into the container when it starts. The official Postgres image is specifically written to *read these three variables on its very first startup* and do the setup for you: create a database named `authservice`, create a login role named `authservice` with that password, and make that role the owner of that database. This is the automated equivalent of the `CREATE DATABASE` / `CREATE USER` / `GRANT` commands from Q3 — except it only happens the first time the container is created with a fresh, empty data directory.

```yaml
    ports:
      - "5432:5432"
```
`"host:container"`. Postgres inside the container is listening on its container-internal port `5432` — but by default, nothing outside that container can reach it. This line punches a hole: "take port `5432` on my actual laptop, and forward it straight into port `5432` inside the container." That's the only reason `localhost:5432` in `application.properties` works — without this line, our Spring Boot app (running outside Docker) would have no way to reach Postgres at all.

```yaml
    volumes:
      - postgres-data:/var/lib/postgresql/data
```
Containers are normally **disposable** — stop and remove one, and everything written inside it (including the database files!) is gone. `/var/lib/postgresql/data` is the folder *inside* the container where Postgres actually stores its data files. This line says: "don't let that folder's contents live and die with the container — instead, back it with a named volume called `postgres-data`, which Docker manages separately and keeps around." Result: you can `docker compose down` (stop + remove the containers) and `docker compose up -d` again later, and all your users/rows are still there, because they were never really "inside" the disposable container to begin with.

```yaml
  redis:
    image: redis:7
    ports:
      - "6379:6379"
```
The same two ideas, applied to Redis: use the official `redis:7` image, and forward the container's port `6379` to `localhost:6379` so the app can reach it. Notice there's no `volumes:` here — we're using Redis purely as a cache in this project (see [[RedisCacheConfig]] once we build it), so it's fine for its data to disappear when the container is removed; nothing we can't just recompute or refetch.

```yaml
volumes:
  postgres-data:
```
This *declares* the named volume that `postgres`'s `volumes:` section referenced above. Docker Compose requires named volumes to be listed here at the top level before a service can use them — think of it as "register this storage area with Docker" (Docker creates and manages the actual files on your disk; you never touch them directly).

---

## One sentence to keep

**`docker-compose.yml` doesn't run our app — it runs the two things our app expects to already be running (Postgres, Redis), reproducibly, with one command, using the exact same versions and credentials every single time, for everyone who clones this repo.**
