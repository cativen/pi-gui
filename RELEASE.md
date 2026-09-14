# Publishing Pi GUI to the JetBrains Marketplace

Current state: 299 tests pass, and the Plugin Verifier reports **Compatible** against all four
IDEs it checks (2024.3 × 2, 2025.1, 2025.2) with no remaining scheduled-for-removal API usage.

Work through the steps in order. Steps 1–3 are the ones that block a first upload.

---

## 1. Fill in the vendor details — REQUIRED

Marketplace review rejects submissions without a public contact email. Edit
`gradle.properties`:

```properties
pluginVendorName=Your Name or Org
pluginVendorEmail=you@example.com
pluginVendorUrl=https://github.com/you/pi-gui
```

`publishPlugin` refuses to run while `pluginVendorEmail` is empty, so this cannot be forgotten
on a later automated release.

## 2. Lock the plugin ID — PERMANENT

```
id = "dev.pi.pigui"      // build.gradle.kts
```

This cannot be changed after the first upload; changing it later means a new listing with zero
installs and zero reviews. If you would rather namespace it to a domain or GitHub org you
actually control (for example `io.github.<you>.pigui`), change it **now**.

It must not start with `com.intellij` or `com.jetbrains`; this one does not.

## 3. Decide the licence

The upload form asks for one, and there is currently **no `LICENSE` file in the repo**. Pick a
licence, add the file, and state the same one on the listing. This is a legal choice, so it is
yours to make — MIT and Apache-2.0 are the usual picks for a free IDE plugin.

Note the plugin bundles two third-party libraries, both Apache-2.0, which is compatible with
either choice:

| Bundled | Size | Why |
| --- | --- | --- |
| `sqlite-jdbc` | 14.1 MB | reading cc-switch's provider database |
| `gson` | 283 KB | pi's JSON protocol |

That makes the artifact ~14.9 MB, well under any Marketplace limit, but it is worth knowing
that 94% of the download is one driver used by a single import feature.

## 4. Pre-flight

```bash
cd /Users/zhoutao/code/pi-gui && ./gradlew clean test verifyPlugin buildPlugin
```

Expected: tests green, `Compatible` for every IDE, artifact at
`build/distributions/pi-gui-1.0.0.zip`.

One deprecation warning remains (`FileChooserDescriptorFactory.createSingleFileDescriptor`).
It is a warning, not a blocker, and does not affect review.

## 5. Create the vendor profile

1. Sign in at <https://plugins.jetbrains.com> with a JetBrains Account.
2. Open <https://plugins.jetbrains.com/author/me> and create a **vendor** profile.
   Individual profiles are immediate; organisation profiles need a short verification.

## 6. First upload — by hand

The first version of a plugin must be uploaded manually. The API can only publish updates to a
listing that already exists.

Upload `build/distributions/pi-gui-1.0.0.zip` at <https://plugins.jetbrains.com/plugin/add>.

Suggested category: **Code tools** (or **AI**).

Then wait for moderation — a human reviews first submissions, typically a few business days.

## 7. After approval

Add screenshots on the plugin page. They are not required to pass review, but the listing looks
bare without them, and this plugin is entirely visual. Good candidates: the chat with a
streaming reply, the `/` command popup, the Edits panel with a diff open, and the settings
dialog.

## 8. Later releases — automated

1. Create a permanent token at <https://plugins.jetbrains.com/author/me/tokens>.
2. Bump `version` in `build.gradle.kts` and add a new `changeNotes` section above the old one.
3. Publish:

```bash
export PI_PUBLISH_TOKEN=your-marketplace-token
```

```bash
cd /Users/zhoutao/code/pi-gui && ./gradlew publishPlugin
```

To publish to a pre-release channel users must opt into:

```bash
cd /Users/zhoutao/code/pi-gui && ./gradlew publishPlugin -PpluginChannel=eap
```

## 9. Signing — optional

Marketplace signs unsigned uploads with its own certificate, so this is not required. Signing
yourself proves the artifact came from you.

Generate a key pair once, keeping both files **out of the repository**:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
```

```bash
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Then:

```bash
export PI_CERTIFICATE_CHAIN=/absolute/path/chain.crt
export PI_PRIVATE_KEY=/absolute/path/private.pem
export PI_PRIVATE_KEY_PASSWORD=the-passphrase
```

```bash
cd /Users/zhoutao/code/pi-gui && ./gradlew publishPlugin
```

`signPlugin` already sits between `buildPlugin` and `publishPlugin`; with those variables unset
it is skipped.

---

## What reviewers tend to flag for this kind of plugin

- **It depends on an external CLI.** pi is not bundled. The description says so, and the
  in-IDE error when `pi` is missing explains how to install it. Keep both.
- **Naming.** "Pi GUI" refers to the third-party pi agent. The description explicitly states no
  affiliation with JetBrains or with pi's authors — keep that line.
- **`until-build` is intentionally open.** The plugin only uses stable platform APIs, which is
  why the scheduled-for-removal `ToolWindowManagerListener.toolWindowShown(String, ToolWindow)`
  overload was replaced. If a future IDE does break it, ship an update that pins `untilBuild`
  rather than leaving users on a broken version.
- **Network access.** The settings dialog reaches skills.sh and pi.dev to search for skills and
  packages. If the listing is asked about data handling, that is the whole of it: search
  queries only, no telemetry.
