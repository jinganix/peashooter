import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const gradlePropsPath = join(repoRoot, "gradle.properties");
const readmePaths = ["README.md", "README.zh.md"].map((name) =>
  join(repoRoot, name),
);

function readGradleVersion() {
  const gradleText = readFileSync(gradlePropsPath, "utf8");
  const line = gradleText
    .split("\n")
    .find((entry) => entry.startsWith("version ="));
  if (!line) {
    throw new Error("sync-versions: version not found in gradle.properties");
  }
  return line.split("=")[1].trim().replace(/-SNAPSHOT$/, "");
}

function parseArgs() {
  const args = process.argv.slice(2);
  let consumerVersion = null;
  let syncConsumerFiles = false;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--consumer-version") {
      const value = args[index + 1];
      if (value === undefined || value.startsWith("--")) {
        console.error("sync-versions: --consumer-version requires a value");
        process.exitCode = 1;
        return { consumerVersion: null, syncConsumerFiles: false, invalid: true };
      }
      consumerVersion = args[++index];
      syncConsumerFiles = true;
    } else if (args[index] === "--all") {
      syncConsumerFiles = true;
    }
  }
  return { consumerVersion, syncConsumerFiles };
}

// x.y.z plus an optional fourth segment and optional pre-release/build suffixes
// (e.g. 0.0.11, 0.0.11.1, 1.0.0-rc.1, 1.0.0+build.5). The old x.y.z-only pattern matched a
// prefix of longer versions (silently corrupting 0.0.11.1 into <target>.1) and missed
// suffixed ones entirely while still reporting success.
const VERSION_PATTERN = "[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+][0-9A-Za-z.-]+)?";

function syncReadme(readmePath, version) {
  const content = readFileSync(readmePath, "utf8");

  const gradlePattern = new RegExp(
    `(io\\.github\\.jinganix\\.peashooter:peashooter:)${VERSION_PATTERN}`,
    "g",
  );
  const mavenPattern = new RegExp(
    `(<artifactId>peashooter<\\/artifactId>\\s*\n\\s*<version>)${VERSION_PATTERN}(<\\/version>)`,
    "g",
  );
  // Count replacements in one pass per pattern (a /g test() would be lastIndex-stateful).
  let matches = 0;
  const updated = content
    .replace(gradlePattern, (match, prefix) => {
      matches++;
      return `${prefix}${version}`;
    })
    .replace(mavenPattern, (match, prefix, suffix) => {
      matches++;
      return `${prefix}${version}${suffix}`;
    });
  if (matches === 0) {
    return "missing";
  }

  if (updated !== content) {
    writeFileSync(readmePath, updated);
    return "changed";
  }
  return "unchanged";
}

const devVersion = readGradleVersion();
const { consumerVersion, syncConsumerFiles, invalid } = parseArgs();
if (invalid) {
  process.exit(1);
}
if (consumerVersion !== null) {
  const fullMatch = new RegExp(`^${VERSION_PATTERN}$`);
  if (!fullMatch.test(consumerVersion)) {
    console.error(`sync-versions: invalid --consumer-version '${consumerVersion}'`);
    process.exit(1);
  }
}
const targetVersion = consumerVersion ?? devVersion;

let anyChanged = false;
let anyMissing = false;
if (syncConsumerFiles) {
  for (const readmePath of readmePaths) {
    const status = syncReadme(readmePath, targetVersion);
    if (status === "changed") {
      anyChanged = true;
    } else if (status === "missing") {
      console.error(`sync-versions: no version reference found in ${readmePath}`);
      anyMissing = true;
    }
  }
}

if (anyMissing) {
  // A requested sync that matches nothing is a format drift (or a broken pattern), never a
  // successful no-op: fail loudly instead of reporting "unchanged".
  process.exitCode = 1;
} else if (anyChanged) {
  console.log(`sync-versions: consumer ${targetVersion}, dev ${devVersion}`);
} else {
  console.log(`sync-versions: dev ${devVersion} (unchanged)`);
}
