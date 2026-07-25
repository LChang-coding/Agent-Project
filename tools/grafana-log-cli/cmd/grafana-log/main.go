// Command grafana-log queries remote Loki logs through Grafana.
package main

import (
	"os"

	"github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/cli"
)

func main() {
	os.Exit(cli.Execute())
}
