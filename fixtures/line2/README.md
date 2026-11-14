# Line2 definition candidates

`station-model-1.0.0.json` and `tool-model-1.0.0.json` are the exact bytes `mimir derive` writes when it browses the Bifrost sim's `StationType` and `ToolType` in namespace `urn:bifrost:opcua:sim`. They are candidates; Bifrost admits and publishes them.

Source: Bifrost commit `11fdad9`, `sim/target/bifrost-sim.jar` built with `mvn -B -ntp -q -pl sim -am package -DskipTests`, listening on `opc.tcp://localhost:48400`.

Commands:

    java -jar target/mimir.jar derive opc.tcp://localhost:48400 urn:bifrost:opcua:sim StationType station-model 1.0.0 station-model-1.0.0.json
    java -jar target/mimir.jar derive opc.tcp://localhost:48400 urn:bifrost:opcua:sim ToolType tool-model 1.0.0 tool-model-1.0.0.json

`scripts/derive-line2-definitions.sh` starts the sim from a sibling Bifrost checkout (`BIFROST_DIR`, default `../bifrost`) and runs both commands; with `--check` it compares the output with these files byte for byte and exits 1 on any difference.

`.gitattributes` keeps these files byte for byte (`-text`), because consumers pin them by SHA-256.
