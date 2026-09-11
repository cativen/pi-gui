# Publishing Pi GUI to the JetBrains Marketplace

## 0. Before the first upload

Fill in `gradle.properties` — Marketplace review requires a public contact email, and an
upload with a blank one gets bounced:

```properties
pluginVendorName=Your Name or Org
pluginVendorEmail=you@example.com
pluginVendorUrl=https://github.com/you/pi-gui
```

The build refuses to run `publishPlugin` while `pluginVendorEmail` is empty.

Also decide on the plugin ID. It is **permanent** once published and cannot be changed:

```
dev.pi.pigui
```

If you want it namespaced to a domain or GitHub org you control, change `id` in
`build.gradle.kts` *now* — after the first upload you would have to create a new listing.

Marketplace also expects the ID not to start with `com.intellij` / `com.jetbrains`, which this
one does not.

## 1. Create a JetBrains account and vendor profile

1. Sign in at <https://plugins.jetbrains.com> with a JetBrains Account.
2. Open <https://plugins.jetbrains.com/author/me> and create a **vendor** profile
   (individual or organization). Organization vendors need a short verification step.

## 2. First upload — manual

The very first version of a plugin has to be uploaded by hand; the API can only publish
updates to a listing that already exists.

```bash
./gradlew clean buildPlugin
```

Upload `build/distributions/pi-gui-1.0.0.zip` at
<https://plugins.jetbrains.com/plugin/add>, and pick a category —
**Code tools** or **AI** fits this plugin.

Then wait for moderation. First submissions are reviewed by a human; expect a few business
days. Rejections usually cite a missing contact email, a description that is too thin or not
in English, or a name that implies an official JetBrains product.

## 3. Later releases — automated

Once the listing exists, updates can be pushed from the command line.

1. Create a permanent token at <https://plugins.jetbrains.com/author/me/tokens>.
2. Bump `version` in `build.gradle.kts` and update `changeNotes` in the same file.
3. Publish:

```bash
export PI_PUBLISH_TOKEN=your-marketplace-token
./gradlew publishPlugin
```

To publish to a pre-release channel that users must opt into, set `pluginChannel` (in
`gradle.properties` or on the command line):

```bash
./gradlew publishPlugin -PpluginChannel=eap
```

## 4. Signing (optional but recommended)

IDEs verify plugin signatures. The Marketplace will sign an unsigned upload with its own
certificate, but signing yourself proves the artifact came from you.

Generate a key pair once:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Keep both files out of the repository, then:

```bash
export PI_CERTIFICATE_CHAIN=/absolute/path/chain.crt
export PI_PRIVATE_KEY=/absolute/path/private.pem
export PI_PRIVATE_KEY_PASSWORD=the-passphrase
./gradlew publishPlugin
```

`signPlugin` already sits between `buildPlugin` and `publishPlugin` in the task graph; with
those variables unset it is simply skipped.

## 5. Pre-flight checklist

```bash
./gradlew clean test verifyPlugin
```

- `test` — 78 unit and headless-UI tests
- `verifyPlugin` — JetBrains Plugin Verifier against the locally installed IDE

Current status: **Compatible** against IU-261.22158.277, one deprecated-API usage
(`FileChooserDescriptorFactory.createSingleFileDescriptor`), which is a warning, not a
blocker.

To widen coverage before a release, verify against more IDEs by editing the
`pluginVerification.ides` block in `build.gradle.kts`:

```kotlin
ides {
    recommended()          // JetBrains' recommended matrix — downloads several GB
    // or pin specific ones:
    // ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.1")
    // ide(IntelliJPlatformType.GoLand, "2025.1")
}
```

## 6. Things reviewers tend to flag for this kind of plugin

- **External dependency.** The plugin needs the `pi` CLI, which is not bundled. The
  description already states this; keep it prominent, and make sure the in-IDE error when
  `pi` is missing explains how to install it (it does).
- **`until-build` is intentionally open.** The plugin only uses stable platform APIs, so it
  is declared compatible with future IDEs. If a future release breaks it, publish an update
  that pins `untilBuild` rather than leaving users on a broken version.
- **Naming.** "Pi GUI" refers to the third-party pi agent, not to JetBrains. Avoid wording
  that suggests official affiliation with JetBrains or with the pi authors.
- **Screenshots.** Not required to pass review, but the listing looks bare without them. Add
  them on the plugin page after approval.
