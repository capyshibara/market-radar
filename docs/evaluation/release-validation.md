# Release validation

The release harness validates a candidate Market Radar tree without starting the application,
calling an LLM/model provider, running the pipeline, or reading/writing live application data.

## Run it

From `market-radar/` (or invoke the script by absolute path):

```bash
./scripts/validate_release.sh
```

Prerequisites are Bash, Maven, Java/Javac and Python 3. Maven dependencies must already be available
or Maven must be able to resolve them. The harness creates compiler scratch files under the operating
system temporary directory and normal Maven build output under `target/`; it does not delete or
rewrite source, fixtures, reports or application data.

## What it proves

The harness runs these gates in order and stops on the first unexpected failure:

1. Refuse to run when a process is listening on TCP port `8081`. This prevents validation from being
   mistaken for, or interleaved with, a running Market Radar application.
2. Run `mvn -DskipTests package`. Packaging compiles the checked-out application but does not start it.
3. Ask Maven for the resolved dependency classpath, discover every root-level `*Test.java`, compile
   each discovered file against `target/classes` plus those dependencies, then run every matching
   class with Java assertions enabled.
4. Enforce the current deterministic Product golden-set targets.
5. Run the legacy-label evaluator as a negative control and require exit code `1`. Exit `0` means the
   gate no longer discriminates; exit `2` (or any other status) is an evaluator/infrastructure error.
6. Run the Product publication-quality regression fixtures, including edition minimum-quality cases.

The script prints the number of root standalone tests it discovered. There is no maintained test-name
allowlist, so a new root `*Test.java` automatically joins the release gate.

## What it does not prove

It does not run Spring Boot, browse/fetch sources, invoke model APIs, reprocess a database, publish a
report or approve content. It also does not replace human Product judgment. After it passes, review
the candidate edition with the [Product-SME checklist](product-sme-review-checklist.md). The release
requires every scored dimension to meet the 4/5 threshold and no mandatory stop condition.

## Failure handling

- **Port 8081 active:** stop the existing application/process deliberately, verify why it was
  running, and rerun. The harness will not terminate it.
- **Maven build/classpath failure:** fix the compile or dependency issue; do not bypass the build.
- **Standalone test failure:** use the printed compile/run class name to reproduce it with the same
  built `target/classes` and Maven dependencies.
- **Current golden failure:** inspect the printed case IDs and deterministic rule version. Do not
  replace the current-rules run with supplied predictions for release approval.
- **Legacy control unexpectedly passes:** treat the evaluation design as broken even if all other
  tests pass.
- **Publication fixture failure:** compare the exact disposition/failure codes with
  `docs/evaluation/product-publication-quality-cases.json`.

Keep the failed output with the candidate build/commit identifier. After a fix, rerun the entire
harness rather than only the failed stage, then complete a fresh Product-SME review.
