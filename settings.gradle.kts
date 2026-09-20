rootProject.name = "courtpulse"

include(
    "modules:domain",
    "modules:providers",
    "modules:persistence",
    "modules:testkit",
    "apps:replay-cli",
    "apps:durable-replay-cli",
)
