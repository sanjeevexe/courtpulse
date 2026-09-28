# Shortcuts for the common workflows. Every target wraps a script or command documented in
# README.md and docs/guide.md; run `make` to list them. Requires Docker, Java 21, and Node.js 24.

.DEFAULT_GOAL := help
TERRAFORM := docker run --rm -v "$(CURDIR)":/w -w /w hashicorp/terraform:1.16.4
TERRAFORM_OFFLINE := docker run --rm --network none -v "$(CURDIR)":/w -w /w hashicorp/terraform:1.16.4
.PHONY: help demo demo-status demo-down demo-destroy test web-check check perf recovery security \
	observability acceptance terraform-check

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  %-17s %s\n", $$1, $$2 }'

demo: ## Start the full local demo (simulated live game, sign-in, alerts, email)
	scripts/demo.sh up

demo-status: ## Show demo services, URLs, and usernames
	scripts/demo.sh status

demo-down: ## Stop the demo, keeping its data
	scripts/demo.sh down

demo-destroy: ## Stop the demo and delete its data and images
	scripts/demo.sh destroy

test: ## Backend tests (needs Docker for PostgreSQL and LocalStack containers)
	./gradlew test --console=plain

web-check: ## Frontend contract, type, lint, unit, and build checks
	cd apps/web && npm ci && npm run check:api && npm run typecheck && npm run lint \
		&& npm run test:run && npm run build

check: test web-check ## Everything CI's fast jobs run locally

perf: ## Performance suite (about 10 minutes; see docs/verification/performance-report.md)
	scripts/run-performance-tests.sh

recovery: ## Failure drills (about 8 minutes; see docs/verification/recovery-report.md)
	scripts/verify-recovery.sh

security: ## OWASP ZAP baseline against an isolated stack
	scripts/verify-security-baseline.sh

observability: ## Start the local Grafana, Prometheus, and Tempo stack
	scripts/start-observability.sh

acceptance: ## Longer end-to-end harnesses (observability, live provider, release rehearsal)
	scripts/verify-observability.sh
	scripts/verify-milestone-12.sh
	scripts/verify-release-rehearsal.sh

terraform-check: ## Offline Terraform checks in a container (no AWS account or credentials used)
	$(TERRAFORM) fmt -check -recursive infra/terraform
	for root in bootstrap environments/staging; do \
		$(TERRAFORM) -chdir=infra/terraform/$$root init -backend=false -input=false >/dev/null \
			&& $(TERRAFORM) -chdir=infra/terraform/$$root validate || exit 1; \
	done
	$(TERRAFORM_OFFLINE) -chdir=infra/terraform/environments/staging test
