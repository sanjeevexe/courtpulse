rootProject.name = "courtpulse"

include(
    "modules:domain",
    "modules:providers",
    "modules:persistence",
    "modules:messaging",
    "modules:testkit",
    "apps:replay-cli",
    "apps:durable-replay-cli",
    "apps:queue-replay-cli",
)
