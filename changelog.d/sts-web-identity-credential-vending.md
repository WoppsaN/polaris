### Upgrade notes

- `STS_WEB_IDENTITY`: a node running an earlier build cannot vend credentials for an
  `STS_WEB_IDENTITY` catalog, so create such catalogs only after every node runs a build that
  supports the mechanism.

### New Features

- S3 catalogs can set `credentialVendingMechanism` to `STS_WEB_IDENTITY` to vend credentials
  through STS AssumeRoleWithWebIdentity, for S3-compatible stores whose STS accepts web identity
  but not AssumeRole, such as Pure Storage FlashBlade. Polaris sends its own token, read on every
  credential mint from the file set by the new `polaris.storage.aws.web-identity-token-file`
  property (for example a Kubernetes projected service account token); the caller's token is never
  sent. The realm must list `STS_WEB_IDENTITY` in `SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS`.
  Creating or updating such a catalog fails with HTTP 400 when the server cannot read a token from
  that file.
