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
    if (args[index] === "--consumer-version" && args[index + 1]) {
      consumerVersion = args[++index];
      syncConsumerFiles = true;
    } else if (args[index] === "--all") {
      syncConsumerFiles = true;
    }
  }
  return { consumerVersion, syncConsumerFiles };
}

function syncReadme(readmePath, version) {
  let content = readFileSync(readmePath, "utf8");
  const original = content;

  content = content.replace(
    /(io\.github\.jinganix\.peashooter:peashooter:)[0-9]+\.[0-9]+\.[0-9]+/g,
    `$1${version}`,
  );
  content = content.replace(
    /(<artifactId>peashooter<\/artifactId>\s*\n\s*<version>)[0-9]+\.[0-9]+\.[0-9]+(<\/version>)/g,
    `$1${version}$2`,
  );

  if (content !== original) {
    writeFileSync(readmePath, content);
    return true;
  }
  return false;
}

const devVersion = readGradleVersion();
const { consumerVersion, syncConsumerFiles } = parseArgs();
const targetVersion = consumerVersion ?? devVersion;

let anyChanged = false;
if (syncConsumerFiles) {
  for (const readmePath of readmePaths) {
    if (syncReadme(readmePath, targetVersion)) {
      anyChanged = true;
    }
  }
}

if (anyChanged) {
  console.log(`sync-versions: consumer ${targetVersion}, dev ${devVersion}`);
} else {
  console.log(`sync-versions: dev ${devVersion} (unchanged)`);
}
