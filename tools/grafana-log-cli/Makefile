.PHONY: test verify build release skill-validate

test:
	go test ./...

verify:
	go test -race ./...
	go vet ./...

build:
	mkdir -p dist
	CGO_ENABLED=0 go build -trimpath -buildvcs=false -mod=readonly \
		-ldflags "-s -w -X github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/cli.Version=dev" \
		-o dist/grafana-log ./cmd/grafana-log

release:
	./scripts/build-release.sh

skill-validate:
	python3 /Users/codeliu/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
		skill/grafana-log-inspector
