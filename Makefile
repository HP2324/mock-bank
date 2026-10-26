# Everyday commands. Run `make` (or `make help`) to list them.
.DEFAULT_GOAL := help
.PHONY: help up down reset logs infra run run-consumer dev test verify replay

MVNW := ./mvnw -B
PY   := .venv/bin/python
BASE_URL ?= http://localhost:8080

help: ## List the available commands
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "} {printf "  make %-13s %s\n", $$1, $$2}'

up: ## Build and start the whole stack in Docker (http://localhost:8080)
	docker compose up -d --build --wait

down: ## Stop the stack, keeping its data
	docker compose down

reset: ## Stop the stack, wipe its data, and start again from the seed data
	docker compose down -v
	docker compose up -d --build --wait

logs: ## Follow the logs of every service
	docker compose logs -f

infra: ## Start only PostgreSQL and Kafka (for running the services from source)
	docker compose up -d --wait postgres kafka

run: infra ## Run bank-api from source on :8080 (builds the React app first)
	$(MVNW) -pl services/bank-api spring-boot:run

run-consumer: ## Run audit-consumer from source on :8081
	$(MVNW) -pl services/audit-consumer spring-boot:run

dev: ## Start the Vite dev server with hot reload on :5173 (needs bank-api on :8080)
	cd frontend && npm install && npm run dev

test: ## Fast tests: Java unit tests, frontend tests, automation tests
	$(MVNW) -DskipFrontend test
	cd frontend && ([ -d node_modules ] || npm ci) && npm test
	cd automation && $(PY) -m pytest -q

verify: ## All Java tests, including Testcontainers integration tests (needs Docker)
	$(MVNW) -DskipFrontend verify

replay: ## Replay every saved automation artifact against BASE_URL and check its outcome
	cd automation && PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers $(PY) scripts/replay_smoke.py --base-url $(BASE_URL)
