// Copyright (C) 2017 The Bazel Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import build.bazel.ci.JenkinsUtils

@NonCPS
private def pruneIfOlderThan(file, timestamp) throws IOException {
  if (file.isDirectory()) {
    boolean empty = true
    for (child in file.listFiles()) {
      if (!pruneIfOlderThan(child, timestamp)) {
        empty = false
      }
    }
    if (empty) {
      if (file.delete()) {
        return true
      }
    }
  } else if (file.lastModified() < timestamp) {
    if (file.delete()) {
      return true
    }
  }
  return false
}

@NonCPS
private def pruneOldCustomBazel(node_label) {
  try {
    def dir = new File(getBazelInstallPath(node_label, "custom")).parentFile
    pruneIfOlderThan(dir,
                     System.currentTimeMillis() - 172800000 /* 2 days */)
  } catch(IOException ex) {
    // Several error can occurs, we ignore them all as this step
    // is just for convenience, not critical.
  }
}

private def getBazelInstallPath(node_label, String... segments) {
  return node_label.startsWith("windows") ?
      "c:\\bazel_ci\\installs\\${segments.join '\\'}\\bazel.exe" :
      "${env.HOME}/.bazel/${segments.join '/'}/binary/bazel"
}

@NonCPS
private def touchFileIfExists(path) {
  // Touch a file anywhere on the FS
  def f = new File(path)
  def r = f.exists()
  if (r) {
    try {
      f.setLastModified(System.currentTimeMillis())
    } catch(IOException ex) {
      // The file might be busy, especially on windows, swallowing exception
    }
  }
  return r
}

/**
 * A step that returns the path of a specific version of the bazel binary. If that version is
 * set to "custom", it will for look for the bazel binary in the list of artifacts transmitted
 * by upstream job (not yet implemented). In the other case, it looks for bazel in a well
 * known location.
 */
def call(String bazel_version, String node_label) {
  // Grab bazel
  if (bazel_version.startsWith("custom")) {
    // A custom version can be completed with a variation, e.g. custom-jdk7, extract it
    def variation = bazel_version.substring(6)
    node_label = JenkinsUtils.normalizeNodeLabel(node_label)
    def cause = JenkinsUtils.findAndCopyUpstreamArtifacts(
      currentBuild,
      "^node=${node_label}/variation=${variation}/bazel(\\.exe)?\$")

    if (cause == null) {
      error("Failed to find upstream cause while asked to build with custom Bazel")
    }

    echo "Using Bazel binary from upstream project ${cause.upstreamProject} build #${cause.upstreamBuild} at path ${cause.artifactPath}"
    def bazel = getBazelInstallPath(
      node_label,
      "custom",
      cause.upstreamProject.toString().replaceAll("/", "_"),
      cause.upstreamBuild.toString(),
      "variation_${variation}")
    if (!touchFileIfExists(bazel)) {
      dir(".bazel") { deleteDir() }
      step([$class: 'CopyArtifact',
            filter: cause.artifactPath,
            fingerprintArtifacts: true,
            flatten: true,
            projectName: cause.upstreamProject,
            selector: [$class: 'SpecificBuildSelector',
                       buildNumber: "${cause.upstreamBuild}"],
            target: ".bazel/"])
      if (node_label.startsWith("windows")) {
        // File.parent is null, use custom substring
        def bazelDir = bazel.substring(0, bazel.lastIndexOf("\\"))
        bat "mkdir ${bazelDir}\r\nmove /Y ${pwd()}\\.bazel\\bazel.exe ${bazel}"
      } else {
        def bazelDir = bazel.substring(0, bazel.lastIndexOf("/"))
        sh "mkdir -p ${bazelDir}; mv .bazel/bazel ${bazel}"
      }
    }
    pruneOldCustomBazel(node_label)
    echo "Using custom version of Bazel at ${bazel}"
    return bazel
  } else {
    def bazel = getBazelInstallPath(node_label, bazel_version)
    echo "Using released version of Bazel at ${bazel}"
    return bazel
  }
}
