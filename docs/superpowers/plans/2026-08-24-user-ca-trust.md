# User CA Trust Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge pull request #54 while preserving its contributor authorship and prove that Voyager trusts user-installed certificate authorities without weakening TLS hostname or chain validation.

**Architecture:** Android's declarative Network Security Configuration supplies application-wide system and user trust anchors for arbitrary user-configured WebDAV hosts. A source contract test locks the manifest and XML resource relationship before the contributor commit is applied.

**Tech Stack:** Android manifest resources, Network Security Configuration, JUnit 4, Gradle, GitHub CLI.

**Spec:** `docs/superpowers/specs/2026-08-24-github-issues-52-58-design.md`

## Global Constraints

- Preserve author André Apitzsch and commit `1cae2a1b76d7a860f97888052a96f82c42bb340a` from pull request #54.
- Trust only Android's `system` and `user` certificate stores.
- Keep hostname verification and normal TLS chain validation enabled.
- Preserve `android:usesCleartextTraffic="true"` for explicit FTP and HTTP WebDAV connections.
- Do not introduce a custom `TrustManager`, hostname verifier, or arbitrary leaf-certificate bypass.

---

### Task 1: Network security regression contract

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/security/NetworkSecurityConfigTest.kt`

**Interfaces:**
- Consumes: `app/src/main/AndroidManifest.xml` and `app/src/main/res/xml/network_security_config.xml`.
- Produces: a JVM contract that rejects a missing manifest declaration, missing trust stores, or TLS bypass primitives.

- [ ] **Step 1: Write the failing source contract test**

```kotlin
package com.voyagerfiles.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    @Test
    fun manifestUsesSystemAndUserCertificateAuthorities() {
        val root = repositoryRoot()
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        val configFile = root.resolve("app/src/main/res/xml/network_security_config.xml")

        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(configFile.isFile)
        val config = configFile.readText()
        assertTrue(config.contains("<certificates src=\"system\""))
        assertTrue(config.contains("<certificates src=\"user\""))
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"true\""))
        assertFalse(config.contains("overridePins=\"true\""))
    }

    private fun repositoryRoot(): File {
        val cwd = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(cwd, checkNotNull(cwd.parentFile))
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    }
}
```

- [ ] **Step 2: Run the test and verify the red state**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.security.NetworkSecurityConfigTest --stacktrace`

Expected: FAIL because the manifest has no `networkSecurityConfig` attribute and the XML file does not exist.

- [ ] **Step 3: Commit the failing regression test**

```bash
git add app/src/test/java/com/voyagerfiles/security/NetworkSecurityConfigTest.kt
git commit -m "test: require user certificate authority trust"
```

### Task 2: Contributor implementation

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Consumes: Android `android:networkSecurityConfig` and base trust anchors.
- Produces: application-wide trust for user-installed and system authorities.

- [ ] **Step 1: Fetch and inspect the exact pull request head**

Run: `git fetch origin pull/54/head:refs/remotes/origin/pr-54-user-ca && git show --stat --oneline 1cae2a1b76d7a860f97888052a96f82c42bb340a && git diff HEAD...1cae2a1b76d7a860f97888052a96f82c42bb340a -- app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml`

Expected: only the manifest attribute and Network Security Configuration are relevant, and the fetched commit resolves to the recorded contributor commit.

- [ ] **Step 2: Apply the contributor commit without changing its author**

Run: `git cherry-pick 1cae2a1b76d7a860f97888052a96f82c42bb340a`

Expected XML behavior:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="user" />
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 3: Run focused and manifest-merge verification**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.security.NetworkSecurityConfigTest processDebugMainManifest --stacktrace`

Expected: PASS and the merged debug manifest references `@xml/network_security_config` while retaining `usesCleartextTraffic="true"`.

- [ ] **Step 4: Inspect the resulting commit authorship and security diff**

Run: `git show -s --format='author=%an <%ae>%ncommitter=%cn <%ce>%nsubject=%s' HEAD && git diff HEAD^ -- app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml`

Expected: contributor author is preserved; the diff contains no custom code, hostname-verification changes, or credential material.

### Task 3: Documentation and GitHub pull request resolution

**Files:**
- Modify: `README.md`
- Modify: `docs/TESTING.md`

**Interfaces:**
- Produces: factual instructions for user-installed authorities and a manual HTTPS WebDAV certificate check.

- [ ] **Step 1: Document the behavior**

Add this concise README statement under WebDAV security:

```markdown
HTTPS WebDAV follows Android's system and user-installed certificate authorities. A private authority must be installed by the device owner, and normal certificate-chain and hostname validation still apply.
```

Add this test case to the Remote row in `docs/TESTING.md`:

```text
HTTPS WebDAV signed by a disposable user-installed CA succeeds for the matching hostname and fails for a hostname mismatch.
```

- [ ] **Step 2: Run documentation and focused build checks**

Run: `git diff --check && ./gradlew testDebugUnitTest --tests com.voyagerfiles.security.NetworkSecurityConfigTest lintDebug --stacktrace`

Expected: PASS with no Markdown whitespace errors or Android lint findings.

- [ ] **Step 3: Commit the documentation**

```bash
git add README.md docs/TESTING.md
git commit -m "docs: explain private WebDAV certificate trust"
```

- [ ] **Step 4: Push, approve the first-time contributor workflow, and resolve pull request #54 only after CI passes**

Run: `git push -u origin codex/github-issues-52-58`

Use GitHub Actions to approve the pending workflow for pull request #54, then run: `gh pr checks 54 --watch`

Expected: all pull request checks pass. Keep #54 open until the combined implementation pull request reaches `master`, then close #54 with a factual note that commit `1cae2a1b` was retained in the delivered history.
